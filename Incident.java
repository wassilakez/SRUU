package sruu.ontology;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modèle de données d'un incident.
 * LIEN COURS §3.1-A : chaque incident est une ressource partagée
 * que les agents doivent se coordonner pour traiter.
 */
public class Incident implements Serializable {

    private static final AtomicInteger COUNTER = new AtomicInteger(1);

    public enum Status { PENDING, ASSIGNED, IN_PROGRESS, RESOLVED, UNRESOLVED }

    private final String id;
    private final String type;
    private final int    severity;
    private final int    x, y;
    private final long   detectedAt;
    private volatile Status status;
    private volatile String assignedUnit;
    private volatile long   resolvedAt;

    public Incident(String type, int severity, int x, int y) {
        this.id         = "INC-" + COUNTER.getAndIncrement();
        this.type       = type;
        this.severity   = severity;
        this.x          = x;
        this.y          = y;
        this.detectedAt = System.currentTimeMillis();
        this.status     = Status.PENDING;
    }

    // Getters
    public String getId()           { return id; }
    public String getType()         { return type; }
    public int    getSeverity()     { return severity; }
    public int    getX()            { return x; }
    public int    getY()            { return y; }
    public long   getDetectedAt()   { return detectedAt; }
    public Status getStatus()       { return status; }
    public String getAssignedUnit() { return assignedUnit; }
    public long   getResolvedAt()   { return resolvedAt; }

    // Setters
    public void setStatus(Status s)       { this.status = s; }
    public void setAssignedUnit(String u) { this.assignedUnit = u; }
    public void setResolvedAt(long t)     { this.resolvedAt = t; }

    public long responseTimeMs() {
        if (resolvedAt == 0) return -1;
        return resolvedAt - detectedAt;
    }

    /** Sérialise l'incident pour l'envoyer dans un message ACL */
    public String toACLContent() {
        return EmergencyOntology.serialize(
            EmergencyOntology.KEY_INCIDENT_ID,   id,
            EmergencyOntology.KEY_INCIDENT_TYPE, type,
            EmergencyOntology.KEY_SEVERITY,      String.valueOf(severity),
            EmergencyOntology.KEY_COORD_X,       String.valueOf(x),
            EmergencyOntology.KEY_COORD_Y,       String.valueOf(y)
        );
    }
    public String toACL() {
        return toACLContent();
    }
    @Override
    public String toString() {
        return String.format("[%s | type=%s | sev=%d | pos=(%d,%d) | status=%s]",
                id, type, severity, x, y, status);
    }
}