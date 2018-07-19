/*
 * cred-manager is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2017, Gluu
 */
package org.gluu.credmanager.ui.vm;

import org.gluu.credmanager.core.AuthFlowContext;
import org.gluu.credmanager.core.OxdService;
import org.gluu.credmanager.core.SessionContext;
import org.gluu.credmanager.extension.navigation.MenuType;
import org.gluu.credmanager.extension.navigation.NavigationMenu;
import org.gluu.credmanager.ui.MenuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.util.Pair;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zkplus.cdi.DelegatingVariableResolver;

import java.util.List;

/**
 * @author jgomer
 */
@VariableResolver(DelegatingVariableResolver.class)
public class HeaderViewModel {

    private Logger logger = LoggerFactory.getLogger(getClass());

    @WireVariable
    private OxdService oxdService;

    @WireVariable
    private AuthFlowContext authFlowContext;

    @WireVariable
    private MenuService menuService;

    @WireVariable
    private SessionContext sessionContext;

    private List<Pair<String, NavigationMenu>> contextMenuItems;

    public List<Pair<String, NavigationMenu>> getContextMenuItems() {
        return contextMenuItems;
    }

    @Init
    public void init() {
        contextMenuItems = menuService.getMenusOfType(MenuType.AUXILIARY);
    }

    @Command
    public void logoutFromAuthzServer() {

        try {
            logger.trace("Log off attempt");
            purgeSession();
            //After End-User has logged out, the Client might request to log him out of the OP too
            //TODO: what happens after session expiration?, add in log trace who is logging out
            String idToken = authFlowContext.getIdToken();
            Executions.sendRedirect(oxdService.getLogoutUrl(idToken));
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    }

    private void purgeSession() {
        authFlowContext.setStage(AuthFlowContext.RedirectStage.NONE);
        sessionContext.setUser(null);
    }

}