package sruu.ontology;

/**
 * LIEN COURS §3.2 (AGR) : chaque CAP_* = un RÔLE au sens AGR.
 * Chaque SERVICE_* = un GROUPE.
 * Toute communication passe par ces constantes — jamais de String litérale.
 */
public class EmergencyOntology {

    public static final String ONTOLOGY_NAME = "EmergencyOntology-v1";

    // ── Types d'incidents ─────────────────────────────────────────────────
    public static final String INCIDENT_FIRE                = "FIRE";
    public static final String INCIDENT_MEDICAL             = "MEDICAL";
    public static final String INCIDENT_STRUCTURAL_COLLAPSE = "STRUCTURAL_COLLAPSE";
    public static final String INCIDENT_BIOHAZARD           = "BIOHAZARD";
    public static final String INCIDENT_CRYOGENIC_LEAK      = "CRYOGENIC_LEAK";

    // ── Rôles AGR (capacités enregistrées dans le DF) ────────────────────
    public static final String CAP_MEDICAL               = "MEDICAL";
    public static final String CAP_FIRE                  = "FIRE";
    public static final String CAP_RESCUE                = "RESCUE";
    public static final String CAP_CROWD_CONTROL         = "CROWD_CONTROL";
    public static final String CAP_PERIMETER             = "PERIMETER";
    public static final String CAP_BIOHAZARD_CONTAINMENT = "BIOHAZARD_CONTAINMENT";

    // ── Groupes AGR (types de services dans le DF) ───────────────────────
    public static final String SERVICE_DISPATCHER     = "DispatcherService";
    public static final String SERVICE_MEDICAL_COORD  = "MedicalCoordinatorService";
    public static final String SERVICE_TRAFFIC        = "TrafficControllerService";
    public static final String SERVICE_LOGGER         = "LoggerService";

    // ── Clés des messages ACL ─────────────────────────────────────────────
    public static final String KEY_INCIDENT_ID       = "incidentId";
    public static final String KEY_INCIDENT_TYPE     = "incidentType";
    public static final String KEY_SEVERITY          = "severity";
    public static final String KEY_COORD_X           = "x";
    public static final String KEY_COORD_Y           = "y";
    public static final String KEY_UNIT_STATUS       = "unitStatus";
    public static final String KEY_UNIT_TYPE         = "unitType";
    public static final String KEY_ETA               = "eta";
    public static final String KEY_HOSPITAL_NAME     = "hospitalName";
    public static final String KEY_BEDS_AVAILABLE    = "bedsAvailable";
    public static final String KEY_CORRIDOR_TIMEOUT  = "corridorTimeout";
    public static final String KEY_ABORT_REASON      = "abortReason";

    // ── Statuts des unités ────────────────────────────────────────────────
    public static final String STATUS_IDLE      = "IDLE";
    public static final String STATUS_EN_ROUTE  = "EN_ROUTE";
    public static final String STATUS_ON_SCENE  = "ON_SCENE";
    public static final String STATUS_RETURNING = "RETURNING";
    public static final String STATUS_ACTIVE    = "ACTIVE";

    // ── Codes événements (dans le conversation-id ou le content) ─────────
    public static final String PERF_ABORT            = "ABORT";
    public static final String PERF_ARRIVED          = "ARRIVED";
    public static final String PERF_RESOLVED         = "RESOLVED";
    public static final String PERF_PERIMETER_SECURE = "PERIMETER_SECURE";
    public static final String PERF_CORRIDOR_OPEN    = "CORRIDOR_OPEN";
    public static final String PERF_SATURATION_ALERT = "SATURATION_ALERT";
    public static final String PERF_LOG_EVENT        = "LOG_EVENT";
    public static final String PERF_HOSPITAL_REPLY   = "HOSPITAL_REPLY";

    // ── Sérialisation format "cle=valeur;cle=valeur" ──────────────────────
    public static String serialize(String... keyValues) {
        if (keyValues.length % 2 != 0)
            throw new IllegalArgumentException("Paires cle-valeur obligatoires");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(";");
            sb.append(keyValues[i]).append("=").append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    public static java.util.Map<String, String> deserialize(String content) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (content == null || content.isBlank()) return map;
        for (String pair : content.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) map.put(kv[0].trim(), kv[1].trim());
        }
        return map;
    }

    public static String get(String content, String key) {
        return deserialize(content).getOrDefault(key, "");
    }
}
