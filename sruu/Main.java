package sruu;

/**
 * Lance le système SRUU complet.
 * Ordre : coordinateurs EN PREMIER, puis unités terrain.
 * Raison : les unités cherchent le Dispatcher dans le DF au démarrage.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String[] jadeArgs = {
            "-gui",
            // ── Coordinateurs (Membre 1) ──────────────────────────────────
            "dispatcher:sruu.agents.DispatcherAgent",
            "medCoord:sruu.agents.MedicalCoordinatorAgent",
            "traffic:sruu.agents.TrafficControllerAgent",
            "logger:sruu.agents.LoggerAgent",

            // ── Capteurs (Membre 2) ───────────────────────────────────────
            "sensor1:sruu.agents.SensorAgent",
            "sensor2:sruu.agents.SensorAgent",

            // ── Ambulances (Membre 2) — argument = position initiale ──────
            "ambulance1:sruu.agents.AmbulanceAgent(2,3)",
            "ambulance2:sruu.agents.AmbulanceAgent(15,12)",
            "ambulance3:sruu.agents.AmbulanceAgent(8,18)",

            // ── Pompiers (Membre 2) ───────────────────────────────────────
            "firetruck1:sruu.agents.FireTruckAgent(1,1)",
            "firetruck2:sruu.agents.FireTruckAgent(19,19)",

            // ── Police (Membre 2) ─────────────────────────────────────────
            "police1:sruu.agents.PoliceAgent(5,5)",
            "police2:sruu.agents.PoliceAgent(14,7)",

            // ── BCU (Membre 2) ────────────────────────────────────────────
            "bcu1:sruu.agents.BCUAgent(10,10)",
        };

        System.out.println("=== DÉMARRAGE SRUU ===");
        jade.Boot.main(jadeArgs);
    }
}
