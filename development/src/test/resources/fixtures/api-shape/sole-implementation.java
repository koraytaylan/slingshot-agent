package rs.slingshot.agent.fixture;

/** An interface with exactly one implementation, wrongly named. */
public interface Reader {

    /**
     * Reads something.
     *
     * @return what it read
     */
    String read();
}

/** The only implementation, which is not named for its interface. */
final class FileReader implements Reader {

    @Override
    public String read() {
        return "";
    }
}
