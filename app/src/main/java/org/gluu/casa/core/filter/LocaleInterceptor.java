package org.gluu.casa.core.filter;

import org.gluu.casa.core.ZKService;
import org.gluu.casa.misc.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xdi.util.StringHelper;
import org.zkoss.web.Attributes;
import org.zkoss.zk.ui.Session;
import org.zkoss.zk.ui.util.RequestInterceptor;

import javax.servlet.ServletRequest;
import java.util.Locale;
import java.util.Set;

/**
 * @author jgomer
 * This class solves the problem described here: http://forum.zkoss.org/question/110980/how-to-constrain-to-a-set-of-locales/
 */
public class LocaleInterceptor implements RequestInterceptor {

    private Locale defaultLocale = Locale.ENGLISH;

    private Logger logger =  LoggerFactory.getLogger(getClass());

    private ZKService zkService;

    public LocaleInterceptor() {
        logger.info("Locale filter initialized");
        zkService = Utils.managedBean(ZKService.class);
    }

    public void request(Session session, Object request, Object response) {

        try {
            if (session.getAttribute(Attributes.PREFERRED_LOCALE) == null) {
                Set<Locale> allowed = zkService.getSupportedLocales();

                if (allowed != null) {
                    //The set of allowed locales is ready (may be empty though)
                    Locale val = defaultLocale;
                    Locale requestedLocale = ((ServletRequest) request).getLocale();

                    if (allowed.contains(requestedLocale)) {
                        val = requestedLocale;
                    } else {
                        String language = requestedLocale.getLanguage();

                        if (Utils.isNotEmpty(language)) {
                            val = allowed.stream().filter(loc -> StringHelper.equalsIgnoreCase(loc.getLanguage(), language))
                                    .findFirst().orElse(defaultLocale);
                        }
                    }
                    logger.info("Locale for this session will be '{}'", val);
                    session.setAttribute(Attributes.PREFERRED_LOCALE, val);
                } else {
                    logger.warn("Supported locales not known yet");
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    }

}
