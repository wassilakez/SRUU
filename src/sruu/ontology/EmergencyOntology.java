package sruu.ontology;

public class EmergencyOntology {

    // Types d'incidents
    public static final String INCIDENT_FIRE                = "FIRE";
    public static final String INCIDENT_MEDICAL             = "MEDICAL";
    public static final String INCIDENT_STRUCTURAL_COLLAPSE = "STRUCTURAL_COLLAPSE";
    public static final String INCIDENT_BIOHAZARD           = "BIOHAZARD";
    public static final String INCIDENT_CRYOGENIC_LEAK      = "CRYOGENIC_LEAK";

    // Capacités DF
    public static final String CAP_MEDICAL               = "MEDICAL";
    public static final String CAP_FIRE                  = "FIRE";
    public static final String CAP_RESCUE                = "RESCUE";
    public static final String CAP_CROWD_CONTROL         = "CROWD_CONTROL";
    public static final String CAP_PERIMETER             = "PERIMETER";
    public static final String CAP_BIOHAZARD_CONTAINMENT = "BIOHAZARD_CONTAINMENT";

    // Noms de services DF
    public static final String SERVICE_EMERGENCY_UNIT      = "EmergencyUnit";
    public static final String SERVICE_MEDICAL_COORDINATOR = "MedicalCoordinator";
    public static final String SERVICE_LOGGER              = "IncidentLogger";

    // Clés des messages ACL
    public static final String KEY_INCIDENT_ID   = "incidentId";
    public static final String KEY_INCIDENT_TYPE = "type";
    public static final String KEY_SEVERITY      = "severity";
    public static final String KEY_X             = "x";
    public static final String KEY_Y             = "y";
    public static final String KEY_STATUS        = "status";
    public static final String KEY_DISTANCE      = "distance";
    public static final String KEY_UNIT_TYPE     = "unitType";
    public static final String KEY_WORKLOAD      = "workload";
    public static final String KEY_ETA           = "eta";
    public static final String KEY_HOSPITAL      = "hospital";
    public static final String KEY_MESSAGE       = "message";

    // Valeurs de statut
    public static final String STATUS_IDLE       = "IDLE";
    public static final String STATUS_EN_ROUTE   = "EN_ROUTE";
    public static final String STATUS_ON_SCENE   = "ON_SCENE";
    public static final String STATUS_RETURNING  = "RETURNING";
    public static final String STATUS_PATROLLING = "PATROLLING";
    public static final String STATUS_ABORT      = "ABORT";
    public static final String STATUS_RESOLVED   = "RESOLVED";

    // Ontologie ACL
    public static final String ONTOLOGY_NAME = "EmergencyOntology-v1";
    public static final String LANGUAGE      = "fipa-sl";

    // Utilitaires
    public static String buildContent(Object... keyValues) {
        if (keyValues.length % 2 != 0)
            throw new IllegalArgumentException("Paires clé/valeur requises");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(';');
            sb.append(keyValues[i]).append(':').append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    public static String getValue(String content, String key) {
        if (content == null) return null;
        for (String pair : content.split(";")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) return kv[1].trim();
        }
        return null;
    }

    public static int getIntValue(String content, String key, int defaultVal) {
        String v = getValue(content, key);
        if (v == null) return defaultVal;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultVal; }
    }

    public static double getDoubleValue(String content, String key, double defaultVal) {
        String v = getValue(content, key);
        if (v == null) return defaultVal;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return defaultVal; }
    }
}