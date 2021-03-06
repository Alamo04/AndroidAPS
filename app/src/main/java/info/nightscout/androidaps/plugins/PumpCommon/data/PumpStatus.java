package info.nightscout.androidaps.plugins.PumpCommon.data;

import java.util.Date;

import info.nightscout.androidaps.data.ProfileStore;
import info.nightscout.androidaps.interfaces.PumpDescription;
import info.nightscout.androidaps.plugins.PumpCommon.defs.PumpStatusType;

/**
 * Created by andy on 4/28/18.
 */

public abstract class PumpStatus {

    public Date lastDataTime;
    public long lastConnection = 0L;
    public Date lastBolusTime;
    public String activeProfileName = "0";
    public double reservoirRemainingUnits = 0d;
    public String reservoirFullUnits = "???";
    public int batteryRemaining = 0; // percent, so 0-100
    public String iob = "0";
    protected PumpDescription pumpDescription;
    public boolean validBasalRateProfileSelectedOnPump = true;

    public ProfileStore profileStore;
    public String units; // Constants.MGDL or Constants.MMOL
    public PumpStatusType pumpStatusType = PumpStatusType.Running;

    // TODO maybe not needed anymore in 2.0
    public Double constraintBasalRateAbsolute;
    public Integer constraintBasalRatePercent;
    public Double constraintBolus;
    public Integer constraintCarbs;
    public Double constraintMaxIob;


    public PumpStatus(PumpDescription pumpDescription) {
        this.pumpDescription = pumpDescription;

        this.initSettings();
    }


    public abstract void initSettings();


    public void setLastDataTimeToNow() {
        this.lastDataTime = new Date();
        this.lastConnection = System.currentTimeMillis();
    }


    public abstract String getErrorInfo();


    public abstract void refreshConfiguration();

}
