public class RoutingInfo {
    public String username;
    public byte address;
    public byte nextHopAddress;
    public int TTL;

    public RoutingInfo (String username, byte address, byte nextHopAddress) {
        this.username = username;
        this.address = address;
        this.nextHopAddress = nextHopAddress;
    }
}
