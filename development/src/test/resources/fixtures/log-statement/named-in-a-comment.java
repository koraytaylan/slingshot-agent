package rs.slingshot.agent.fixture;

/**
 * A class that explains why LoggerFactory and String.format are refused, and does neither.
 *
 * <p>A rule that could not tell naming a form from using it would be a rule that stopped people
 * explaining why the form is refused, and /var/slingshot-agent is the path it would name.</p>
 */
public final class NamedInAComment {

    /** Writes one, the way this repository writes them. */
    public void write() {
        // LoggerFactory and String.format and /var/slingshot-agent are named here and not used.
        AgentLog.lineOf(LogEvent.of("swept").with("kinds", "4"), value -> false, 4096);
    }
}
