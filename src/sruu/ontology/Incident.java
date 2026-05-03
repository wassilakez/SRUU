package sruu.ontology;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

public class Incident implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final AtomicInteger counter = new AtomicInteger(0);

    public enum State { PENDING, ASSIGNED, IN_PROGRESS, RESOLVED, UNRESOLVED }

    private final String id;
    private final String type;
    private final int severity;
    private final int x, y;
    private final long timestamp;

    private State state;
    private String assignedUnitId;
    private long responseTime;

    public Incident(String type, int severity, int x, int y) {
        this.id        = "INC-" + String.format("%04d", counter.incrementAndGet());
        this.type      = type;
        this.severity  = severity;
        this.x         = x;
        this.y         = y;
        this.timestamp = System.currentTimeMillis();
        this.state     = State.PENDING;
    }

    public static Incident fromACL(String content) {
        String type  = EmergencyOntology.getValue(content, EmergencyOntology.KEY_INCIDENT_TYPE);
        int severity = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_SEVERITY, 1);
        int x        = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_X, 0);
        int y        = EmergencyOntology.getIntValue(content, EmergencyOntology.KEY_Y, 0);
        return new Incident(type, severity, x, y);
    }

    public String toACL() {
        return EmergencyOntology.buildContent(
            EmergencyOntology.KEY_INCIDENT_ID, id,
            EmergencyOntology.KEY_INCIDENT_TYPE, type,
            EmergencyOntology.KEY_SEVERITY, severity,
            EmergencyOntology.KEY_X, x,
            EmergencyOntology.KEY_Y, y,
            "timestamp", timestamp
        );
    }

    public double distanceTo(int ux, int uy) {
        int dx = x - ux, dy = y - uy;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public String getId()           { return id; }
    public String getType()         { return type; }
    public int    getSeverity()     { return severity; }
    public int    getX()            { return x; }
    public int    getY()            { return y; }
    public long   getTimestamp()    { return timestamp; }
    public State  getState()        { return state; }
    public String getAssignedUnit() { return assignedUnitId; }

    public void setState(State s)         { this.state = s; }
    public void setAssignedUnit(String u) { this.assignedUnitId = u; }
    public void setResponseTime(long t)   { this.responseTime = t; }

    @Override
    public String toString() {
        return String.format("[%s | %s | sev=%d | (%d,%d) | %s]", id, type, severity, x, y, state);
    }
}