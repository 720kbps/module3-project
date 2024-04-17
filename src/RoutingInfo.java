public class RoutingInfo {
    private String username;
    private int source;
    private int nextHop;


    public RoutingInfo(int source, int nextHop, String username) {
        this.source = source;
        this.nextHop = nextHop;
        this.username = username;
    }

    public int getSource() {
        return source;
    }
    public int getNextHop() {
        return nextHop;
    }

    public String getUsername() {
        return username;
    }
}
