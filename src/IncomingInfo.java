public class IncomingInfo {
    public String username;
    public byte address;
    public int seq;
    public String message;
    public boolean toBeForwarded;
    public boolean fullMessageArrived;

    public IncomingInfo(String username, byte address, int seq,
                        String message, boolean toBeForwarded, boolean fullMessageArrived) {
        this.username = username;
        this.address = address;
        this.seq = seq;
        this.message = message;
        this.toBeForwarded = toBeForwarded;
        this.fullMessageArrived = fullMessageArrived;
    }
}
