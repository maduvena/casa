package org.gluu.casa.ui;

import org.zkoss.util.resource.Labels;

/**
 * @author jgomer
 */
public enum CredRemovalConflict {
    CREDS2FA_NUMBER_UNDERFLOW("usr.del_conflict_underflow"),
    REQUISITE_NOT_FULFILED("usr.del_conflict_requisite");

    private String messageKey;

    CredRemovalConflict(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getMessage() {
        return Labels.getLabel(messageKey);
    }

    public String getMessage(Object ...args) {
        return Labels.getLabel(messageKey, args);
    }

}
