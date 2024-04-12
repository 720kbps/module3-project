public class RoutingInfo {
    private String username;
    private int destination;
    private int nextHop;


    public RoutingInfo(int destination, int nextHop, String username) {
        this.destination = destination;
        this.nextHop = nextHop;
        this.username = username;
    }

    public int getDestination() {
        return destination;
    }

    public int getNextHop() {
        return nextHop;
    }

    public String getUsername() {
        return username;
    }
}
