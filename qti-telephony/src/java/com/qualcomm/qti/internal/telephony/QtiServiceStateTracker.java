package com.qualcomm.qti.internal.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ZeroBalanceHelper;
import android.os.AsyncResult;
import android.os.Bundle;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.ConfigResourceUtil;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.ServiceStateTracker;
import com.qti.internal.telephony.QtiPlmnOverride;
import com.qualcomm.qcrilhook.QmiOemHookConstants;
import com.qualcomm.qcrilhook.QmiPrimitiveTypes;

public class QtiServiceStateTracker extends ServiceStateTracker {
    private static final String ACTION_MANAGED_ROAMING_IND = 
                                 "codeaurora.intent.action.ACTION_MANAGED_ROAMING_IND";

    private static final String LOG_TAG = "QtiServiceStateTracker";
    private final String ACTION_RAC_CHANGED = "qualcomm.intent.action.ACTION_RAC_CHANGED";
    private final String mRacChange = "rac";
    private final String mRatInfo = "rat";
    
    
    private int mRac;
    private int mRat;
    private int mTac = -1;
    
    private ConfigResourceUtil mConfigResUtil = new ConfigResourceUtil();
    private QtiPlmnOverride mQtiPlmnOverride = new QtiPlmnOverride();
    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_RAC_CHANGED)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    QtiServiceStateTracker.this.mRac = bundle.getInt(mRacChange);
                    QtiServiceStateTracker.this.mRat = bundle.getInt(mRatInfo);
                    QtiServiceStateTracker.this.enableBackgroundData();
                }
            }
        }
    };

    public QtiServiceStateTracker(GsmCdmaPhone phone, CommandsInterface ci) {
        super(phone, ci);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_RAC_CHANGED);
        phone.getContext().registerReceiver(this.mIntentReceiver, filter);
    }

    private void enableBackgroundData() {
        ZeroBalanceHelper helper = new ZeroBalanceHelper();
        if (helper.getFeatureConfigValue() && helper.getBgDataProperty().equals("true")) {
            Log.i("zerobalance", "Enabling the background data on LAU/RAU");
            helper.setBgDataProperty("false");
        }
    }

    protected void handlePollStateResultMessage(int what, AsyncResult ar) {
        switch (what) {
            case QmiPrimitiveTypes.SIZE_OF_INT /*4*/:
                super.handlePollStateResultMessage(what, ar);
                if (this.mPhone.isPhoneTypeGsm()) {
                    String[] states = (String[]) ar.result;
                    int regState = ServiceState.RIL_REG_STATE_UNKNOWN;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);
                        } catch (NumberFormatException ex) {
                            loge("error parsing RegistrationState: " + ex);
                        }
                    }
                    if ((regState == ServiceState.RIL_REG_STATE_DENIED
                              || regState == ServiceState.RIL_REG_STATE_DENIED_EMERGENCY_CALL_ENABLED)
                              && states.length >= 14) {

                        try {
                            if (Integer.parseInt(states[13]) == 10) {
                                log(" Posting Managed roaming intent sub = " + this.mPhone.getSubId());
                                Intent intent = new Intent(ACTION_MANAGED_ROAMING_IND);
                                intent.putExtra("subscription", this.mPhone.getSubId());
                                this.mPhone.getContext().sendBroadcast(intent);
                                return;
                            }
                            return;
                        } catch (NumberFormatException ex2) {
                            loge("error parsing regCode: " + ex2);
                            return;
                        }
                    }
                    return;
                }
                return;
            case QmiOemHookConstants.RESPONSE_BUFFER /*6*/:
                super.handlePollStateResultMessage(what, ar);
                if (this.mPhone.isPhoneTypeGsm()) {
                    String[] opNames = (String[]) ar.result;
                    if (opNames != null && opNames.length >= 3) {
                        String brandOverride;
                        if (this.mUiccController.getUiccCard(getPhoneId()) != null) {
                            brandOverride = this.mUiccController.getUiccCard(getPhoneId()).
                                                                getOperatorBrandOverride();
                        } else {
                            brandOverride = null;
                        }
                        if (brandOverride != null) {
                            log("EVENT_POLL_STATE_OPERATOR: use brandOverride=" + brandOverride);
                            this.mNewSS.setOperatorName(brandOverride, brandOverride, opNames[2]);
                            return;
                        }
                        if (this.mQtiPlmnOverride.containsCarrier(opNames[2])) {
                            ConfigResourceUtil configResourceUtil = this.mConfigResUtil;
                            if (ConfigResourceUtil.getBooleanValue(this.mPhone.getContext(),
                                                              "config_plmn_name_override_enabled")) {

                                log("EVENT_POLL_STATE_OPERATOR: use plmnOverride");
                                this.mNewSS.setOperatorName(this.mQtiPlmnOverride.getPlmn(opNames[2]),
                                                                              opNames[1], opNames[2]);
                                return;
                            }
                        }
                        this.mNewSS.setOperatorName(opNames[0], opNames[1], opNames[2]);
                        return;
                    }
                    return;
                }
                return;
            default:
                super.handlePollStateResultMessage(what, ar);
                return;
        }
    }

    protected void setRoamingType(ServiceState currentServiceState) {
        boolean isVoiceInService = false;
        super.setRoamingType(currentServiceState);
        if (currentServiceState.getVoiceRegState() == 0) {
            isVoiceInService = true;
        }
        if (isVoiceInService && currentServiceState.getVoiceRoaming() && this.mPhone.isPhoneTypeGsm()) {
            setOperatorConsideredDomesticRoaming(currentServiceState);
        }
    }

    private void setOperatorConsideredDomesticRoaming(ServiceState s) {
        String operatorNumeric = s.getOperatorNumeric();
        if (operatorNumeric == null || TextUtils.isEmpty(operatorNumeric)) {
            return;
        }
        int subId = this.mPhone.getSubId();
        String[] numericArray = SubscriptionManager.getResourcesForSubId(this.mPhone.getContext(), subId).
                        getStringArray(com.android.internal.R.array.config_operatorConsideredDomesticRoaming);

        String[] numericExceptionsArray = SubscriptionManager.getResourcesForSubId(this.mPhone.getContext(), subId).
                     getStringArray(com.android.internal.R.array.config_operatorConsideredDomesticRoamingExceptions);

        if (numericArray != null && numericArray.length != 0) {
            int length;
            boolean isDomestic = false;
            for (String numeric : numericArray) {
                if (operatorNumeric.startsWith(numeric)) {
                    s.setVoiceRoamingType(ServiceState.ROAMING_TYPE_DOMESTIC);
                    isDomestic = true;
                    break;
                }
            }
            if (numericExceptionsArray.length != 0 && isDomestic) {
                int i = 0;
                length = numericExceptionsArray.length;
                while (i < length) {
                    if (operatorNumeric.startsWith(numericExceptionsArray[i])) {
                        s.setVoiceRoamingType(ServiceState.ROAMING_TYPE_INTERNATIONAL);
                        break;
                    }
                    i++;
                }
            }
            if (!isDomestic) {
                s.setVoiceRoamingType(ServiceState.ROAMING_TYPE_INTERNATIONAL);
            }
        }
    }
}
