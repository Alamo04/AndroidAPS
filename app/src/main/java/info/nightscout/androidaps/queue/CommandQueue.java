package info.nightscout.androidaps.queue;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.text.Spanned;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.DetailedBolusInfo;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.events.EventBolusRequested;
import info.nightscout.androidaps.interfaces.Constraint;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.Overview.Dialogs.BolusProgressDialog;
import info.nightscout.androidaps.plugins.Overview.Dialogs.BolusProgressHelperActivity;
import info.nightscout.androidaps.plugins.Overview.events.EventDismissNotification;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.Overview.notifications.Notification;
import info.nightscout.androidaps.queue.commands.Command;
import info.nightscout.androidaps.queue.commands.CommandBolus;
import info.nightscout.androidaps.queue.commands.CommandCancelExtendedBolus;
import info.nightscout.androidaps.queue.commands.CommandCancelTempBasal;
import info.nightscout.androidaps.queue.commands.CommandExtendedBolus;
import info.nightscout.androidaps.queue.commands.CommandLoadEvents;
import info.nightscout.androidaps.queue.commands.CommandLoadHistory;
import info.nightscout.androidaps.queue.commands.CommandLoadTDDs;
import info.nightscout.androidaps.queue.commands.CommandReadStatus;
import info.nightscout.androidaps.queue.commands.CommandSMBBolus;
import info.nightscout.androidaps.queue.commands.CommandSetProfile;
import info.nightscout.androidaps.queue.commands.CommandTempBasalAbsolute;
import info.nightscout.androidaps.queue.commands.CommandTempBasalPercent;

/**
 * Created by mike on 08.11.2017.
 * <p>
 * DATA FLOW:
 * ---------
 * <p>
 * (request) - > ConfigBuilder.getCommandQueue().bolus(...)
 * <p>
 * app no longer waits for result but passes Callback
 * <p>
 * request is added to queue, if another request of the same type already exists in queue, it's removed prior adding
 * but if request of the same type is currently executed (probably important only for bolus which is running long time), new request is declined
 * new QueueThread is created and started if current if finished
 * CommandReadStatus is added automatically before command if queue is empty
 * <p>
 * biggest change is we don't need exec pump commands in Handler because it's finished immediately
 * command queueing if not realized by stacking in different Handlers and threads anymore but by internal queue with better control
 * <p>
 * QueueThread calls ConfigBuilder#connect which is passed to getActivePump().connect
 * connect should be executed on background and return immediately. afterwards isConnecting() is expected to be true
 * <p>
 * while isConnecting() == true GUI is updated by posting connection progress
 * <p>
 * if connect is successful: isConnected() becomes true, isConnecting() becomes false
 * CommandQueue starts calling execute() of commands. execute() is expected to be blocking (return after finish).
 * callback with result is called after finish automatically
 * if connect failed: isConnected() becomes false, isConnecting() becomes false
 * connect() is called again
 * <p>
 * when queue is empty, disconnect is called
 */

public class CommandQueue {
    private static Logger log = LoggerFactory.getLogger(CommandQueue.class);

    private final LinkedList<Command> queue = new LinkedList<>();
    protected Command performing;

    private QueueThread thread = null;

    private PumpEnactResult executingNowError() {
        return new PumpEnactResult().success(false).enacted(false).comment(MainApp.gs(R.string.executingrightnow));
    }

    public boolean isRunning(Command.CommandType type) {
        if (performing != null && performing.commandType == type)
            return true;
        return false;
    }

    private synchronized void removeAll(Command.CommandType type) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).commandType == type) {
                queue.remove(i);
            }
        }
    }

    private synchronized boolean isLastScheduled(Command.CommandType type) {
        if (queue.size() > 0 && queue.get(queue.size() - 1).commandType == type) {
            return true;
        }
        return false;
    }

    private synchronized void inject(Command command) {
        // inject as a first command
        log.debug("QUEUE: Adding as first: " + command.getClass().getSimpleName() + " - " + command.status());
        queue.addFirst(command);
    }

    private synchronized void add(Command command) {
        log.debug("QUEUE: Adding: " + command.getClass().getSimpleName() + " - " + command.status());
        queue.add(command);
    }

    synchronized void pickup() {
        performing = queue.poll();
    }

    synchronized void clear() {
        performing = null;
        for (int i = 0; i < queue.size(); i++) {
            queue.get(i).cancel();
        }

        queue.clear();
    }

    public int size() {
        return queue.size();
    }

    public Command performing() {
        return performing;
    }

    public void resetPerforming() {
        performing = null;
    }

    // After new command added to the queue
    // start thread again if not already running
    protected synchronized void notifyAboutNewCommand() {
        while (thread != null && thread.getState() != Thread.State.TERMINATED && thread.waitingForDisconnect) {
            log.debug("QUEUE: Waiting for previous thread finish");
            SystemClock.sleep(500);
        }
        if (thread == null || thread.getState() == Thread.State.TERMINATED) {
            thread = new QueueThread(this);
            thread.start();
            log.debug("QUEUE: Starting new thread");
        } else {
            log.debug("QUEUE: Thread is already running");
        }
    }

    public static void independentConnect(String reason, Callback callback) {
        CommandQueue tempCommandQueue = new CommandQueue();
        tempCommandQueue.readStatus(reason, callback);
    }

    public synchronized boolean bolusInQueue(){
        if(isRunning(Command.CommandType.BOLUS)) return true;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).commandType == Command.CommandType.BOLUS) {
                return true;
            }
        }
        return false;
    }

    // returns true if command is queued
    public synchronized boolean bolus(DetailedBolusInfo detailedBolusInfo, Callback callback) {
        Command.CommandType type = detailedBolusInfo.isSMB ? Command.CommandType.SMB_BOLUS : Command.CommandType.BOLUS;

        if(type.equals(Command.CommandType.BOLUS) && detailedBolusInfo.carbs > 0 && detailedBolusInfo.insulin == 0){
            type = Command.CommandType.CARBS_ONLY_TREATMENT;
            //Carbs only can be added in parallel as they can be "in the future".
        } else {
            if (isRunning(type)) {
                if (callback != null)
                    callback.result(executingNowError()).run();
                return false;
            }

            // remove all unfinished boluses
            removeAll(type);
        }

        // apply constraints
        detailedBolusInfo.insulin = MainApp.getConstraintChecker().applyBolusConstraints(new Constraint<>(detailedBolusInfo.insulin)).value();
        detailedBolusInfo.carbs = MainApp.getConstraintChecker().applyCarbsConstraints(new Constraint<>((int) detailedBolusInfo.carbs)).value();

        // add new command to queue
        if (detailedBolusInfo.isSMB) {
            add(new CommandSMBBolus(detailedBolusInfo, callback));
        } else {
            add(new CommandBolus(detailedBolusInfo, callback, type));
            if(type.equals(Command.CommandType.BOLUS)) {
                // Bring up bolus progress dialog (start here, so the dialog is shown when the bolus is requested,
                // not when the Bolus command is starting. The command closes the dialog upon completion).
                showBolusProgressDialog(detailedBolusInfo.insulin, detailedBolusInfo.context);
                // Notify Wear about upcoming bolus
                MainApp.bus().post(new EventBolusRequested(detailedBolusInfo.insulin));
            }
        }

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean tempBasalAbsolute(double absoluteRate, int durationInMinutes, boolean enforceNew, Profile profile, Callback callback) {
        if (!enforceNew && isRunning(Command.CommandType.TEMPBASAL)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.TEMPBASAL);

        Double rateAfterConstraints = MainApp.getConstraintChecker().applyBasalConstraints(new Constraint<>(absoluteRate), profile).value();

        // add new command to queue
        add(new CommandTempBasalAbsolute(rateAfterConstraints, durationInMinutes, enforceNew, profile, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean tempBasalPercent(Integer percent, int durationInMinutes, boolean enforceNew, Profile profile, Callback callback) {
        if (!enforceNew && isRunning(Command.CommandType.TEMPBASAL)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.TEMPBASAL);

        Integer percentAfterConstraints = MainApp.getConstraintChecker().applyBasalPercentConstraints(new Constraint<>(percent), profile).value();

        // add new command to queue
        add(new CommandTempBasalPercent(percentAfterConstraints, durationInMinutes, enforceNew, profile, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean extendedBolus(double insulin, int durationInMinutes, Callback callback) {
        if (isRunning(Command.CommandType.EXTENDEDBOLUS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        Double rateAfterConstraints = MainApp.getConstraintChecker().applyBolusConstraints(new Constraint<>(insulin)).value();

        // remove all unfinished
        removeAll(Command.CommandType.EXTENDEDBOLUS);

        // add new command to queue
        add(new CommandExtendedBolus(rateAfterConstraints, durationInMinutes, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean cancelTempBasal(boolean enforceNew, Callback callback) {
        if (!enforceNew && isRunning(Command.CommandType.TEMPBASAL)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.TEMPBASAL);

        // add new command to queue
        add(new CommandCancelTempBasal(enforceNew, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean cancelExtended(Callback callback) {
        if (isRunning(Command.CommandType.EXTENDEDBOLUS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.EXTENDEDBOLUS);

        // add new command to queue
        add(new CommandCancelExtendedBolus(callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean setProfile(Profile profile, Callback callback) {
        if (isThisProfileSet(profile)) {
            log.debug("QUEUE: Correct profile already set");
            if (callback != null)
                callback.result(new PumpEnactResult().success(true).enacted(false)).run();
            return false;
        }

        if (!MainApp.isEngineeringModeOrRelease()) {
            Notification notification = new Notification(Notification.NOT_ENG_MODE_OR_RELEASE, MainApp.gs(R.string.not_eng_mode_or_release), Notification.URGENT);
            MainApp.bus().post(new EventNewNotification(notification));
            if (callback != null)
                callback.result(new PumpEnactResult().success(false).comment(MainApp.gs(R.string.not_eng_mode_or_release))).run();
            return false;
        }

        // Compare with pump limits
        Profile.BasalValue[] basalValues = profile.getBasalValues();
        PumpInterface pump = ConfigBuilderPlugin.getActivePump();

        for (Profile.BasalValue basalValue : basalValues) {
            if (basalValue.value < pump.getPumpDescription().basalMinimumRate) {
                Notification notification = new Notification(Notification.BASAL_VALUE_BELOW_MINIMUM, MainApp.gs(R.string.basalvaluebelowminimum), Notification.URGENT);
                MainApp.bus().post(new EventNewNotification(notification));
                if (callback != null)
                    callback.result(new PumpEnactResult().success(false).comment(MainApp.gs(R.string.basalvaluebelowminimum))).run();
                return false;
            }
        }

        MainApp.bus().post(new EventDismissNotification(Notification.BASAL_VALUE_BELOW_MINIMUM));

        // remove all unfinished
        removeAll(Command.CommandType.BASALPROFILE);

        // add new command to queue
        add(new CommandSetProfile(profile, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean readStatus(String reason, Callback callback) {
        if (isLastScheduled(Command.CommandType.READSTATUS)) {
            log.debug("QUEUE: READSTATUS " + reason + " ignored as duplicated");
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        //removeAll(Command.CommandType.READSTATUS);

        // add new command to queue
        add(new CommandReadStatus(reason, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean loadHistory(byte type, Callback callback) {
        if (isRunning(Command.CommandType.LOADHISTORY)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished 
        removeAll(Command.CommandType.LOADHISTORY);

        // add new command to queue
        add(new CommandLoadHistory(type, callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean loadTDDs(Callback callback) {
        if (isRunning(Command.CommandType.LOADHISTORY)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished
        removeAll(Command.CommandType.LOADHISTORY);

        // add new command to queue
        add(new CommandLoadTDDs(callback));

        notifyAboutNewCommand();

        return true;
    }

    // returns true if command is queued
    public boolean loadEvents(Callback callback) {
        if (isRunning(Command.CommandType.LOADEVENTS)) {
            if (callback != null)
                callback.result(executingNowError()).run();
            return false;
        }

        // remove all unfinished
        removeAll(Command.CommandType.LOADEVENTS);

        // add new command to queue
        add(new CommandLoadEvents(callback));

        notifyAboutNewCommand();

        return true;
    }

    public Spanned spannedStatus() {
        String s = "";
        int line = 0;
        if (performing != null) {
            s += "<b>" + performing.status() + "</b>";
            line++;
        }
        for (int i = 0; i < queue.size(); i++) {
            if (line != 0)
                s += "<br>";
            s += queue.get(i).status();
            line++;
        }
        return Html.fromHtml(s);
    }

    public boolean isThisProfileSet(Profile profile) {
        PumpInterface activePump = ConfigBuilderPlugin.getActivePump();
        Profile current = MainApp.getConfigBuilder().getProfile();
        if (activePump != null && current != null) {
            boolean result = activePump.isThisProfileSet(profile);
            if (!result) {
                log.debug("Current profile: " + current.getData().toString());
                log.debug("New profile: " + profile.getData().toString());
            }
            return result;
        } else return true;
    }

    protected void showBolusProgressDialog(Double insulin, Context context) {
        if (context != null) {
            BolusProgressDialog bolusProgressDialog = new BolusProgressDialog();
            bolusProgressDialog.setInsulin(insulin);
            bolusProgressDialog.show(((AppCompatActivity) context).getSupportFragmentManager(), "BolusProgress");
        } else {
            Intent i = new Intent();
            i.putExtra("insulin", insulin);
            i.setClass(MainApp.instance(), BolusProgressHelperActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            MainApp.instance().startActivity(i);
        }
    }

}
