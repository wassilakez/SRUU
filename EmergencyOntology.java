package sruu.ontology;

import java.util.HashMap;
import java.util.Map;

/**
 * EmergencyOntology — VERSION COMPLÈTE
 * Contient TOUTES les constantes et méthodes utilisées par M1 ET M2.
 */
public class EmergencyOntology {

    // ── Nom ontologie et langage ──────────────────────────────────────────
    public static final String ONTOLOGY_NAME = "EmergencyOntology-v1";
    public static final String LANGUAGE      = "fipa-sl";

    // ── Types d'incidents ─────────────────────────────────────────────────
    public static final String INCIDENT_FIRE                = "FIRE";
    public static final String INCIDENT_MEDICAL             = "MEDICAL";
    public static final String INCIDENT_STRUCTURAL_COLLAPSE = "STRUCTURAL_COLLAPSE";
    public static final String INCIDENT_BIOHAZARD           = "BIOHAZARD";
    public static final String INCIDENT_CRYOGENIC_LEAK      = "CRYOGENIC_LEAK";

    // ── Services DF (types) ───────────────────────────────────────────────
    public static final String SERVICE_DISPATCHER            = "DispatcherService";
    public static final String SERVICE_MEDICAL_COORD         = "MedicalCoordinatorService";
    public static final String SERVICE_MEDICAL_COORDINATOR   = "MedicalCoordinatorService"; // alias M2
    public static final String SERVICE_TRAFFIC               = "TrafficControllerService";
    public static final String SERVICE_LOGGER                = "LoggerService";
    public static final String SERVICE_EMERGENCY_UNIT        = "EmergencyUnit"; // utilisé par M2

    // ── Capacités (capabilities) ──────────────────────────────────────────
    public static final String CAP_MEDICAL               = "MEDICAL";
    public static final String CAP_FIRE                  = "FIRE";
    public static final String CAP_RESCUE                = "RESCUE";
    public static final String CAP_CROWD_CONTROL         = "CROWD_CONTROL";
    public static final String CAP_PERIMETER             = "PERIMETER";
    public static final String CAP_BIOHAZARD_CONTAINMENT = "BIOHAZARD_CONTAINMENT";

    // ── Clés des messages — VERSION M1 ────────────────────────────────────
    public static final String KEY_INCIDENT_ID    = "incidentId";
    public static final String KEY_INCIDENT_TYPE  = "incidentType";
    public static final String KEY_SEVERITY       = "severity";
    public static final String KEY_COORD_X        = "x";   // M1 utilise KEY_COORD_X
    public static final String KEY_COORD_Y        = "y";   // M1 utilise KEY_COORD_Y
    public static final String KEY_ETA            = "eta";
    public static final String KEY_UNIT_TYPE      = "unitType";
    public static final String KEY_HOSPITAL_NAME  = "hospitalName";
    public static final String KEY_BEDS_AVAILABLE = "bedsAvailable";
    public static final String KEY_CORRIDOR_TIMEOUT = "corridorTimeout";

    // ── Clés des messages — VERSION M2 ────────────────────────────────────
    public static final String KEY_X        = "x";        // alias M2 (même valeur)
    public static final String KEY_Y        = "y";        // alias M2 (même valeur)
    public static final String KEY_STATUS   = "status";
    public static final String KEY_DISTANCE = "distance";
    public static final String KEY_WORKLOAD = "workload";
    public static final String KEY_HOSPITAL = "hospital";
    public static final String KEY_MESSAGE  = "message";

    // ── Statuts des unités ────────────────────────────────────────────────
    public static final String STATUS_IDLE       = "IDLE";
    public static final String STATUS_EN_ROUTE   = "EN_ROUTE";
    public static final String STATUS_ON_SCENE   = "ON_SCENE";
    public static final String STATUS_RETURNING  = "RETURNING";
    public static final String STATUS_ACTIVE     = "ACTIVE";
    public static final String STATUS_RESOLVED   = "RESOLVED";
    public static final String STATUS_ABORT      = "ABORT";
    public static final String STATUS_PATROLLING = "PATROLLING";
    public static final String STATUS_STANDBY    = "STANDBY";

    // ── Codes événements ──────────────────────────────────────────────────
    public static final String PERF_ABORT            = "ABORT";
    public static final String PERF_ARRIVED          = "ARRIVED";
    public static final String PERF_RESOLVED         = "RESOLVED";
    public static final String PERF_PERIMETER_SECURE = "PERIMETER_SECURE";
    public static final String PERF_CORRIDOR_OPEN    = "CORRIDOR_OPEN";
    public static final String PERF_SATURATION_ALERT = "SATURATION_ALERT";
    public static final String PERF_LOG_EVENT        = "LOG_EVENT";
    public static final String PERF_HOSPITAL_REPLY   = "HOSPITAL_REPLY";

    // ════════════════════════════════════════════════════════════════════════
    // MÉTHODES DE SÉRIALISATION — utilisées par M1 (serialize/deserialize/get)
    // ════════════════════════════════════════════════════════════════════════

    /** M1 : serialize("key1","val1","key2","val2") → "key1=val1;key2=val2" */
    public static String serialize(String... keyValues) {
        if (keyValues.length % 2 != 0)
            throw new IllegalArgumentException("Paires clé-valeur obligatoires");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(";");
            sb.append(keyValues[i]).append("=").append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    /** M1 : deserialize("key1=val1;key2=val2") → Map */
    public static Map<String, String> deserialize(String content) {
        Map<String, String> map = new HashMap<>();
        if (content == null || content.isBlank()) return map;
        for (String pair : content.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    /** M1 : get(content, "key") → "value" */
    public static String get(String content, String key) {
        return deserialize(content).getOrDefault(key, "");
    }

    // ════════════════════════════════════════════════════════════════════════
    // MÉTHODES SUPPLÉMENTAIRES — utilisées par M2
    // ════════════════════════════════════════════════════════════════════════

    /** M2 : getValue(content, "key") → "value"  (alias de get) */
    public static String getValue(String content, String key) {
        return get(content, key);
    }

    /** M2 : getIntValue(content, "key", defaultVal) → int */
    public static int getIntValue(String content, String key, int defaultVal) {
        String v = get(content, key);
        if (v == null || v.isBlank()) return defaultVal;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    /**
     * M2 : buildContent("key1", value1, "key2", value2, ...)
     * Accepte String ou int ou double comme valeur.
     */
    public static String buildContent(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0)
            throw new IllegalArgumentException("Paires clé-valeur obligatoires");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) sb.append(";");
            sb.append(keysAndValues[i]).append("=").append(keysAndValues[i + 1]);
        }
        return sb.toString();
    }
}