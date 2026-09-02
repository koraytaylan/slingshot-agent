package rs.slingshot.agent.fixture;

/** A class that hands the writer a value the corpus covers. */
public final class CorpusInterpolation {

    /** Writes one. */
    public void write() {
        AgentLog.lineOf(LogEvent.of("swept").with("root", "/var/slingshot-agent"),
                value -> false, 4096);
    }
}
