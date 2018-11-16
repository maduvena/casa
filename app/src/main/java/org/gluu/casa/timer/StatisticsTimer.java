/*
 * cred-manager is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2018, Gluu
 */
package org.gluu.casa.timer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.util.StaticUtils;
import org.gluu.casa.core.ExtensionsManager;
import org.gluu.casa.core.LdapService;
import org.gluu.casa.core.TimerService;
import org.gluu.casa.core.ldap.BaseLdapPerson;
import org.gluu.casa.misc.Utils;
import org.pf4j.DefaultPluginDescriptor;
import org.pf4j.PluginDescriptor;
import org.quartz.JobExecutionContext;
import org.quartz.listeners.JobListenerSupport;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class requires Java SE 8u151 or higher with crypto.policy=unlimited in JRE\lib\security\java.security or
 * a call to Security.setProperty("crypto.policy", "unlimited")
 * @author jgomer
 */
@ApplicationScoped
public class StatisticsTimer extends JobListenerSupport {

    private static final String NEW_LINE = System.getProperty("line.separator");

    private static final String DEFAULT_PUBLICKEY_PATH = "/etc/certs/casa.pub";

    private static final String STATS_PATH = System.getProperty("server.base") + File.separator + "stats";

    private static final String TEMP_PATH = System.getProperty("java.io.tmpdir") + File.separator + "casa";

    private static final long DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);

    private static final int UPDATE_PERIOD = 1;

    private static final String LAST_LOGON_ATTR = "oxLastLogonTime";

    private static final Pattern mailPattern = Pattern.compile(".+?emailAddress=(.+?)" + NEW_LINE);

    private static final String GLUU_CASA_PLUGINS_PREFIX = "org.gluu.casa.plugins";

    private static final int AES_KEY_SIZE = 256;

    @Inject
    private Logger logger;

    @Inject
    private LdapService ldapService;

    @Inject
    private TimerService timerService;

    @Inject
    private ExtensionsManager extManager;

    private String serverName;

    private String email;

    private String quartzJobName;

    private PublicKey pub;

    private ObjectMapper mapper;

    private SecretKeySpec symmetricKey;

    public void activate() {

        try {
            if (pub != null) {
                int oneDay = (int) TimeUnit.DAYS.toSeconds(UPDATE_PERIOD);
                timerService.addListener(this, quartzJobName);
                //Start tomorrow and repeat indefinitely once every day
                timerService.schedule(quartzJobName, oneDay, -1, oneDay);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    }

    @Override
    public String getName() {
        return quartzJobName;
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {

        logger.trace("StatisticsTimer. Running timer job");

        long now = System.currentTimeMillis();
        long todayStartAt = now - now % DAY_IN_MILLIS;
        //t belongs to yesterday
        ZonedDateTime t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(todayStartAt - 1), ZoneOffset.UTC);

        String month = t.getMonth().toString();
        String year = Integer.toString(t.getYear());
        Path tmpFilePath = Paths.get(TEMP_PATH, month + year);
        Path statsPath = Paths.get(STATS_PATH, month + year);

        try {
            boolean tmpExists = Files.isRegularFile(tmpFilePath);
            if (tmpExists) {
                //This prevents to inflate statistics when the app is restarted
                long modified = Files.readAttributes(tmpFilePath, BasicFileAttributes.class).lastModifiedTime().toMillis();
                if (modified > todayStartAt) {
                    return;
                }
            }

            int activeUsers = yesterdayLogins(todayStartAt);
            int daysCovered = 1;
            List<Map<String, Object>> plugins = Collections.emptyList();

            if (tmpExists) {
                byte[] bytes = Files.readAllBytes(tmpFilePath);
                Map<String, Object> currStats = mapper.readValue(bytes, new TypeReference<Map<String, Object>>(){});
                daysCovered = Integer.parseInt(currStats.get("daysCovered").toString()) + 1;
                //This in average of active users during a period
                activeUsers+= (daysCovered - 1) * Integer.parseInt(currStats.get("activeUsers").toString());
                activeUsers = Double.valueOf(Math.rint(Integer.valueOf(activeUsers).doubleValue() / Integer.valueOf(daysCovered).doubleValue())).intValue();
                plugins = (List<Map<String, Object>>) currStats.get("plugins");
            }
            plugins = getPluginInfo(plugins);
            serialize(month, year, activeUsers, daysCovered, plugins, statsPath, tmpFilePath);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    }

    private int yesterdayLogins(long todayStartAt) {
        Filter filter = Filter.createANDFilter(
                Filter.createGreaterOrEqualFilter(LAST_LOGON_ATTR, StaticUtils.encodeGeneralizedTime(todayStartAt - DAY_IN_MILLIS)),
                Filter.createLessOrEqualFilter(LAST_LOGON_ATTR, StaticUtils.encodeGeneralizedTime(todayStartAt - 1))
        );
        return ldapService.find(BaseLdapPerson.class, ldapService.getPeopleDn(), filter).size();

    }

    private List<Map<String, Object>> getPluginInfo(List<Map<String, Object>> plugins) {

        List<PluginDescriptor> pluginSummary = new ArrayList<>();
        extManager.getPlugins().stream()
                .filter(pw -> pw.getDescriptor().getPluginClass().startsWith(GLUU_CASA_PLUGINS_PREFIX))
                .forEach(pw -> pluginSummary.add(
                        new DefaultPluginDescriptor(pw.getPluginId(), null, null, pw.getDescriptor().getVersion(), null, null, null))
                );

        for (Map<String, Object> map : plugins) {
            String pluginId = map.get("pluginId").toString();
            String version = map.get("version").toString();

            //Search this occurrence in currently installed plugins
            if (pluginSummary.stream().anyMatch(pd -> pd.getVersion().equals(version) && pd.getPluginId().equals(pluginId))) {
                map.put("daysUsed", Integer.parseInt(map.get("daysUsed").toString()) + 1);
            }
        }

        List<Map<String, Object>> list = new ArrayList<>(plugins);
        //Search plugins added recently
        for (PluginDescriptor pd : pluginSummary) {
            if (plugins.stream().noneMatch(map -> map.get("pluginId").toString().equals(pd.getPluginId())
                    && map.get("version").toString().equals(pd.getVersion()))) {

                Map<String, Object> map = new LinkedHashMap<>();
                map.put("pluginId", pd.getPluginId());
                map.put("version", pd.getVersion());
                map.put("daysUsed", 1);
                list.add(map);
            }
        }
        return list;

    }

    private void serialize(String month, String year, int activeUsers, int days, List<Map<String, Object>> plugins,
                           Path destination, Path tmpDestination) throws Exception {

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("activeUsers", activeUsers);
        map.put("daysCovered", days);
        map.put("plugins", plugins);

        Files.write(tmpDestination, mapper.writeValueAsBytes(map));

        map.put("serverName", serverName);
        map.put("email", email);

        map.put("month", month);
        map.put("year", year);
        map.put("generatedOn", ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME));

        //See https://stackoverflow.com/questions/1199058/how-to-use-rsa-to-encrypt-files-huge-data-in-c-sharp
        //https://stackoverflow.com/questions/5583379/what-is-the-limit-to-the-amount-of-data-that-can-be-encrypted-with-rsa
        byte[] encrKey = encrypt(symmetricKey.getEncoded(), pub, "RSA/ECB/PKCS1Padding");
        Files.write(destination, encrKey);
        //It always holds that encrKey.size == 256

        byte[] bytes = mapper.writeValueAsBytes(map);
        bytes = encrypt(bytes, symmetricKey, "AES");  // AES/CBC/PKCS5Padding
        Files.write(destination, bytes, StandardOpenOption.APPEND);

    }

    private byte[] encrypt(byte[] data, Key key, String transformation) throws Exception {

        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.ENCRYPT_MODE, key);

        try (CipherInputStream cis = new CipherInputStream(new ByteArrayInputStream(data), cipher)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            copy(cis, bos);
            return bos.toByteArray();
        }

    }

    private void copy(InputStream in, OutputStream out) throws Exception {

        byte[] ibuf = new byte[1024];
        int len;
        while ((len = in.read(ibuf)) != -1) {
            out.write(ibuf, 0, len);
        }

    }

    private Path getPublicKeyPath() {
        return Paths.get(Utils.onWindows() ? System.getProperty("gluu.base") + File.separator + "casa.pub" : DEFAULT_PUBLICKEY_PATH);
    }

    private PublicKey getPubKey() throws Exception {
        byte[] bytes = Files.readAllBytes(getPublicKeyPath());
        X509EncodedKeySpec ks = new X509EncodedKeySpec(bytes);
        return KeyFactory.getInstance("RSA").generatePublic(ks);
    }

    private SecretKeySpec genSymmetricKey() throws Exception {

        KeyGenerator kgen = KeyGenerator.getInstance("AES");
        kgen.init(AES_KEY_SIZE);  //bits size
        SecretKey key = kgen.generateKey();
        return new SecretKeySpec(key.getEncoded(), "AES");

    }

    @PostConstruct
    private void inited() {

        try {
            mapper = new ObjectMapper();
            mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

            quartzJobName = getClass().getSimpleName() + "_timer";
            serverName = ldapService.getIssuerUrl().replace("https://", "");

            try (BufferedReader reader = Files.newBufferedReader(Paths.get("/install/community-edition-setup/setup.log"))) {
                String log = reader.lines().reduce("", (partial, next) -> partial + NEW_LINE + next);
                Matcher m = mailPattern.matcher(log);
                email = m.find() ? m.group(1) : null;
            } catch (Exception e) {
                if (!Utils.onWindows()) {
                    throw  e;
                }
            }

            Files.createDirectories(Paths.get(TEMP_PATH));
            Files.createDirectories(Paths.get(STATS_PATH));

            symmetricKey = genSymmetricKey();
            pub = getPubKey();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

    }

}
