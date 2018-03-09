package org.sunbird.learner.actors.badging;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import org.sunbird.common.models.util.BadgingJsonKey;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.PropertiesCache;

import com.google.gson.JsonObject;

public class BadgingUtil {

    public static final String SUNBIRD_BADGR_SERVER_URL_DEFAULT = "http://localhost:8000";
    public static final String BADGING_AUTHORIZATION_FORMAT = "Token %s";
    public static final String  SUNBIRD_BADGER_CREATE_ASSERTION_URL="/v1/issuer/issuers/{0}/badges/{1}/assertions";
    public static final String  SUNBIRD_BADGER_GETASSERTION_URL="/v1/issuer/issuers/{0}/badges/{1}/assertion/{2}";
    public static final String  SUNBIRD_BADGER_GETALLASSERTION_URL="/v1/issuer/issuers/{0}/badges/{1}/assertion";
    public static final String  SUNBIRD_BADGER_REVOKE_URL="/v1/issuer/issuers/{0}/badges/{1}/assertion/{2}";

    private static PropertiesCache propertiesCache = PropertiesCache.getInstance();


    public static String getBadgrBaseUrl() {
        String badgeraseUrl= SUNBIRD_BADGR_SERVER_URL_DEFAULT;
        if(!ProjectUtil.isStringNullOREmpty(System.getenv(BadgingJsonKey.BADGER_BASE_URL))){
            badgeraseUrl = System.getenv(BadgingJsonKey.BADGER_BASE_URL);
        }else if(!ProjectUtil.isStringNullOREmpty(propertiesCache.getProperty(BadgingJsonKey.BADGER_BASE_URL))){
            badgeraseUrl = propertiesCache.getProperty(BadgingJsonKey.BADGER_BASE_URL);
        }
        return badgeraseUrl;
    }

    public static String getBadgeClassUrl(String issuerSlug) {
        return String.format("%s/v1/issuer/issuers/%s/badges", getBadgrBaseUrl(), issuerSlug);
    }

    public static String getBadgeClassUrl(String issuerSlug, String badgeSlug) {
        return String.format("%s/v1/issuer/issuers/%s/badges/%s", getBadgrBaseUrl(), issuerSlug, badgeSlug);
    }

    public static Map<String, String> getBadgrHeaders() {
        HashMap<String, String> headers = new HashMap<>();

        String authToken = System.getenv(BadgingJsonKey.BADGING_AUTHORIZATION_KEY);
        if(ProjectUtil.isStringNullOREmpty(authToken)){
            authToken = propertiesCache.getProperty(BadgingJsonKey.BADGING_AUTHORIZATION_KEY);
        }
        if (!ProjectUtil.isStringNullOREmpty(authToken)) {
            headers.put("Authorization", String.format(BADGING_AUTHORIZATION_FORMAT, authToken));
        }
        headers.put("Accept", "application/json");
        return headers;
    }

    private static String createUri(Map<String, Object> map, String uri, int placeholderCount) {
        if (placeholderCount == 0) {
            return uri;
        } else if (placeholderCount == 1) {
            return MessageFormat.format(uri, (String) map.get(BadgingJsonKey.ISSUER_SLUG));
        } else if (placeholderCount == 2) {
            return MessageFormat.format(uri, (String) map.get(BadgingJsonKey.ISSUER_SLUG),
                (String) map.get(BadgingJsonKey.BADGE_CLASS_SLUG));
        } else {
            return MessageFormat.format(uri, (String) map.get(BadgingJsonKey.ISSUER_SLUG),
                (String) map.get(BadgingJsonKey.BADGE_CLASS_SLUG));
        }
    }

    /**
     * This method will create url for badger server call.
     * in badger url pattern is first placeholder is issuerSlug, 2nd badgeclass slug
     * and 3rd badgeassertion slug
     * @param map  Map<String, Object>
     * @param uri String
     * @param placeholderCount int
     * @return Stirng url
     */
    public static String createBadgerUrl(Map<String, Object> map, String uri, int placeholderCount) {
        String url = PropertiesCache.getInstance().getProperty("sunbird_badger_baseurl")
            + createUri(map, uri, placeholderCount);
        return url + "?format=json";
    }

    /**
     * This method will create assertion request data from requested map.
     *
     * @param map
     *            Map<String,Object>
     * @return String
     */
    public static String createAssertionReqData(Map<String, Object> map) {
        JsonObject json = new JsonObject();
        json.addProperty(BadgingJsonKey.RECIPIENT_IDENTIFIER, (String) map.get(BadgingJsonKey.RECIPIENT_IDENTIFIER));
        if(!ProjectUtil.isStringNullOREmpty((String) map.get(BadgingJsonKey.EVIDENCE))) {
            json.addProperty(BadgingJsonKey.EVIDENCE, (String) map.get(BadgingJsonKey.EVIDENCE));
        }
        json.addProperty(BadgingJsonKey.CREATE_NOTIFICATION, false);
        return json.toString();
    }

}