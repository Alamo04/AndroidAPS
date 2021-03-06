package info.nightscout.androidaps.plugins.PumpDanaR.activities;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;
import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.DanaRHistoryRecord;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.plugins.PumpDanaR.comm.RecordTypes;
import info.nightscout.androidaps.plugins.PumpDanaR.events.EventDanaRSyncStatus;
import info.nightscout.utils.DateUtil;
import info.nightscout.utils.NSUpload;
import info.nightscout.utils.ToastUtils;

/**
 * Created by mike on 20.07.2016.
 */

public class DanaRNSHistorySync {
    private static Logger log = LoggerFactory.getLogger(DanaRNSHistorySync.class);
    private List<DanaRHistoryRecord> historyRecords;

    public final static int SYNC_BOLUS = 0b00000001;
    public final static int SYNC_ERROR = 0b00000010;
    public final static int SYNC_REFILL = 0b00000100;
    public final static int SYNC_GLUCOSE = 0b00001000;
    public final static int SYNC_CARBO = 0b00010000;
    public final static int SYNC_ALARM = 0b00100000;
    public final static int SYNC_BASALHOURS = 0b01000000;
    public final static int SYNC_ALL = 0b11111111;

    public final static String DANARSIGNATURE = "DANARMESSAGE";

    public DanaRNSHistorySync(List<DanaRHistoryRecord> historyRecords) {
        this.historyRecords = historyRecords;
    }


    public void sync(int what) {
        try {
            Calendar cal = Calendar.getInstance();
            long records = historyRecords.size();
            long processing = 0;
            long uploaded = 0;
            log.debug("Database contains " + records + " records");
            EventDanaRSyncStatus ev = new EventDanaRSyncStatus();
            for (DanaRHistoryRecord record : historyRecords) {
                processing++;
                if (record._id != null) continue;
                //log.debug(record.bytes);
                JSONObject nsrec = new JSONObject();
                ev.message = MainApp.gs(R.string.uploading) + " " + processing + "/" + records + " "; // TODO: translations
                switch (record.recordCode) {
                    case RecordTypes.RECORD_TYPE_BOLUS:
                        if ((what & SYNC_BOLUS) == 0) break;
                        switch (record.bolusType) {
                            case "S":
                                log.debug("Syncing standard bolus record " + record.recordValue + "U " + DateUtil.toISOString(record.recordDate));
                                nsrec.put(DANARSIGNATURE, record.bytes);
                                nsrec.put("eventType", "Meal Bolus");
                                nsrec.put("insulin", record.recordValue);
                                nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                                nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                                NSUpload.uploadCareportalEntryToNS(nsrec);
                                uploaded++;
                                ev.message += MainApp.gs(R.string.danar_sbolus);
                                break;
                            case "E":
                                if (record.recordDuration > 0) {
                                    log.debug("Syncing extended bolus record " + record.recordValue + "U " + DateUtil.toISOString(record.recordDate));
                                    nsrec.put(DANARSIGNATURE, record.bytes);
                                    nsrec.put("eventType", CareportalEvent.COMBOBOLUS);
                                    nsrec.put("insulin", 0);
                                    nsrec.put("duration", record.recordDuration);
                                    nsrec.put("relative", record.recordValue / record.recordDuration * 60);
                                    nsrec.put("splitNow", 0);
                                    nsrec.put("splitExt", 100);
                                    cal.setTimeInMillis(record.recordDate);
                                    cal.add(Calendar.MINUTE, -1 * record.recordDuration);
                                    nsrec.put("created_at", DateUtil.toISOString(cal.getTime()));
                                    nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                                    NSUpload.uploadCareportalEntryToNS(nsrec);
                                    uploaded++;
                                    ev.message += MainApp.gs(R.string.danar_ebolus);
                                } else {
                                    log.debug("NOT Syncing extended bolus record " + record.recordValue + "U " + DateUtil.toISOString(record.recordDate) + " zero duration");
                                }
                                break;
                            case "DS":
                                log.debug("Syncing dual(S) bolus record " + record.recordValue + "U " + DateUtil.toISOString(record.recordDate));
                                nsrec.put(DANARSIGNATURE, record.bytes);
                                nsrec.put("eventType", CareportalEvent.COMBOBOLUS);
                                nsrec.put("insulin", record.recordValue);
                                nsrec.put("splitNow", 100);
                                nsrec.put("splitExt", 0);
                                nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                                nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                                NSUpload.uploadCareportalEntryToNS(nsrec);
                                uploaded++;
                                ev.message += MainApp.gs(R.string.danar_dsbolus);
                                break;
                            case "DE":
                                log.debug("Syncing dual(E) bolus record " + record.recordValue + "U " + DateUtil.toISOString(record.recordDate));
                                nsrec.put(DANARSIGNATURE, record.bytes);
                                nsrec.put("eventType", CareportalEvent.COMBOBOLUS);
                                nsrec.put("duration", record.recordDuration);
                                nsrec.put("relative", record.recordValue / record.recordDuration * 60);
                                nsrec.put("splitNow", 0);
                                nsrec.put("splitExt", 100);
                                cal.setTimeInMillis(record.recordDate);
                                cal.add(Calendar.MINUTE, -1 * record.recordDuration);
                                nsrec.put("created_at", DateUtil.toISOString(cal.getTime()));
                                nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                                NSUpload.uploadCareportalEntryToNS(nsrec);
                                uploaded++;
                                ev.message += MainApp.gs(R.string.danar_debolus);
                                break;
                            default:
                                log.debug("Unknown bolus record");
                                break;
                        }
                        break;
                    case RecordTypes.RECORD_TYPE_ERROR:
                        if ((what & SYNC_ERROR) == 0) break;
                        log.debug("Syncing error record " + DateUtil.toISOString(record.recordDate));
                        nsrec.put(DANARSIGNATURE, record.bytes);
                        nsrec.put("eventType", "Note");
                        nsrec.put("notes", "Error");
                        nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                        nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                        NSUpload.uploadCareportalEntryToNS(nsrec);
                        uploaded++;
                        ev.message += MainApp.gs(R.string.danar_error);
                        break;
                    case RecordTypes.RECORD_TYPE_REFILL:
                        if ((what & SYNC_REFILL) == 0) break;
                        log.debug("Syncing refill record " + record.recordValue + " " + DateUtil.toISOString(record.recordDate));
                        nsrec.put(DANARSIGNATURE, record.bytes);
                        nsrec.put("eventType", "Insulin Change");
                        nsrec.put("notes", "Refill " + record.recordValue + "U");
                        nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                        nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                        NSUpload.uploadCareportalEntryToNS(nsrec);
                        uploaded++;
                        ev.message += MainApp.gs(R.string.danar_refill);
                        break;
                    case RecordTypes.RECORD_TYPE_BASALHOUR:
                        if ((what & SYNC_BASALHOURS) == 0) break;
                        log.debug("Syncing basal hour record " + record.recordValue + " " + DateUtil.toISOString(record.recordDate));
                        nsrec.put(DANARSIGNATURE, record.bytes);
                        nsrec.put("eventType", CareportalEvent.TEMPBASAL);
                        nsrec.put("absolute", record.recordValue);
                        nsrec.put("duration", 60);
                        nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                        nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                        NSUpload.uploadCareportalEntryToNS(nsrec);
                        uploaded++;
                        ev.message += MainApp.gs(R.string.danar_basalhour);
                        break;
                    case RecordTypes.RECORD_TYPE_TB:
                        //log.debug("Ignoring TB record " + record.bytes + " " + DateUtil.toISOString(record.recordDate));
                        break;
                    case RecordTypes.RECORD_TYPE_GLUCOSE:
                        if ((what & SYNC_GLUCOSE) == 0) break;
                        log.debug("Syncing glucose record " + record.recordValue + " " + DateUtil.toISOString(record.recordDate));
                        nsrec.put(DANARSIGNATURE, record.bytes);
                        nsrec.put("eventType", "BG Check");
                        nsrec.put("glucose", Profile.fromMgdlToUnits(record.recordValue, MainApp.getConfigBuilder().getProfileUnits()));
                        nsrec.put("glucoseType", "Finger");
                        nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                        nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                        NSUpload.uploadCareportalEntryToNS(nsrec);
                        uploaded++;
                        ev.message += MainApp.gs(R.string.danar_glucose);
                        break;
                    case RecordTypes.RECORD_TYPE_CARBO:
                        if ((what & SYNC_CARBO) == 0) break;
                        log.debug("Syncing carbo record " + record.recordValue + "g " + DateUtil.toISOString(record.recordDate));
                        nsrec.put(DANARSIGNATURE, record.bytes);
                        nsrec.put("eventType", "Meal Bolus");
                        nsrec.put("carbs", record.recordValue);
                        nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                        nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                        NSUpload.uploadCareportalEntryToNS(nsrec);
                        uploaded++;
                        ev.message += MainApp.gs(R.string.danar_carbohydrate);
                        break;
                    case RecordTypes.RECORD_TYPE_ALARM:
                        if ((what & SYNC_ALARM) == 0) break;
                        log.debug("Syncing alarm record " + record.recordAlarm + " " + DateUtil.toISOString(record.recordDate));
                        nsrec.put(DANARSIGNATURE, record.bytes);
                        nsrec.put("eventType", "Note");
                        nsrec.put("notes", "Alarm: " + record.recordAlarm);
                        nsrec.put("created_at", DateUtil.toISOString(record.recordDate));
                        nsrec.put("enteredBy", "openaps://" + MainApp.gs(R.string.app_name));
                        NSUpload.uploadCareportalEntryToNS(nsrec);
                        uploaded++;
                        ev.message += MainApp.gs(R.string.danar_alarm);
                        break;
                    case RecordTypes.RECORD_TYPE_SUSPEND: // TODO: this too
                    case RecordTypes.RECORD_TYPE_DAILY:
                    case RecordTypes.RECORD_TYPE_PRIME:
                        // Ignore
                        break;
                    default:
                        log.error("Unknown record type");
                        break;
                }
                MainApp.bus().post(ev);
            }
            ev.message = String.format(MainApp.gs(R.string.danar_totaluploaded), uploaded);
            MainApp.bus().post(ev);

        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }
}
