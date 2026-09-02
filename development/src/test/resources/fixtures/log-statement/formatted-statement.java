package rs.slingshot.agent.fixture;

/** A class that hands the writer a message somebody formatted. */
public final class FormattedStatement {

    /** Writes one. */
    public void write(String path) {
        AgentLog.lineOf(LogEvent.of(String.format("loaded %s", path)), value -> false, 4096);
    }
}
