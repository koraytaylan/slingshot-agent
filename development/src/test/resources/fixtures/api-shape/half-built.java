package rs.slingshot.agent.fixture;

/** A built thing that still has a setter. */
public final class HalfBuilt {

    private String text = "";

    /**
     * Changes the text after the thing was built.
     *
     * @param text the text
     */
    public void setText(String text) {
        this.text = text;
    }
}
