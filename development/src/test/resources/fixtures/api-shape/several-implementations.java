package rs.slingshot.agent.fixture;

/** An interface with several implementations, one claiming to be the default. */
public interface Tier {

    /**
     * Runs the tier.
     *
     * @return what it observed
     */
    String run();
}

/** One of several, claiming to be the default. */
final class DefaultTier implements Tier {

    @Override
    public String run() {
        return "";
    }
}

/** Another of several. */
final class QuickstartTier implements Tier {

    @Override
    public String run() {
        return "";
    }
}
