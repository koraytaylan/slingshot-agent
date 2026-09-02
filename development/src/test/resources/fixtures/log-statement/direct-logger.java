package rs.slingshot.agent.fixture;

import org.slf4j.LoggerFactory;

/** A class that writes lines the writer never sees. */
public final class DirectLogger {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(DirectLogger.class);

    /** Writes one. */
    public void write() {
        LOG.info("something happened");
    }
}
