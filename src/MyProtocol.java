import client.Client;
import client.Message;
import client.MessageType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MyProtocol {
    private static String SERVER_IP = "netsys.ewi.utwente.nl";
    private static int SERVER_PORT = 8954;
    private static int frequency = 5800;
    String token = "java-53-ME854K6ZFTSIXDHC2V";

    // Source address from 1 to 254
    // Source address 255 or byte -1 is used for broadcast to all nodes (neighboring)
    private static final byte SRC = (byte) (1 + new Random().nextInt(254));
    private static String srcUsername = ""; // Username of the source from above
    private boolean freeLink = true; // Keeps the link state ~ for CA

    private boolean sending = false;

    private Set<ByteBuffer> packetSet = new HashSet<>();
    private List<RoutingInfo> routingTable = new ArrayList<>();
    private List<IncomingInfo> incomingBuffer = new ArrayList<>();
    private int receivedAck = 0;
    private int sentSeq = 255;

    private boolean failedToSend = false;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    public MyProtocol(String server_ip, int server_port, int frequency)
            throws InterruptedException {

        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();

        new Client(SERVER_IP, SERVER_PORT, frequency, token, receivedQueue, sendingQueue);
        new receiveThread(receivedQueue).start();

        routingMessage.start();
        // clearRoutingTable.start();

        Thread.sleep(1000); // wait 1 second for framework
        chatInit();
        mainChat();
    }

    /**
     * Initializes the chat by prompting the user to enter a username,
     * validating it, and displaying available commands.
     */
    private void chatInit() {
        // Print chat initiation message
        System.out.println("⁶\uD80C\uDD53 ~ ");
        System.out.println("Your source address is: " + SRC + "\nChoose a username please");

        // Input username
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter username: ");
        String username = scanner.nextLine();

        // Validate username
        while (!isValidUsername(username)) {
            System.out.print("Invalid username(>25)\nEnter new username: ");
            username = scanner.nextLine();
        }
        srcUsername = username;

        // Add routing info for the source user
        routingTable.add(new RoutingInfo(srcUsername, SRC, SRC));

        // Initialize routing message
        initRoutingMessage();

        // Print available commands
        System.out.println("\nCommands:\n\nlist        shows the list of active users\n" +
                                   "message     opens the message menu\n" +
                                   "inbox       opens your inbox\n" +
                                   "quit        closes the chat\n" +
                                   "help        to see the messages above\n");
    }

    /**
     * Checks if a username is valid.
     *
     * @param username The username to be validated.
     * @return True if the username is valid, false otherwise.
     */
    private boolean isValidUsername(String username) {
        return username.length() <= 25;
    }

    /**
     * Runs the main chat loop, processing user commands until the user decides to quit.
     */
    private void mainChat() {
        while (true) {
            System.out.print("Enter command: ");
            Scanner scanner = new Scanner(System.in);
            String command = scanner.nextLine();

            // Process user command
            switch (command) {
                case "list":
                    listUsers();
                    break;
                case "message":
                    sendMessage();
                    break;
                case "inbox":
                    checkInbox();
                    break;
                case "help":
                    displayHelp();
                    break;
                case "quit":
                    endChat();
                    return;
                default:
                    System.out.println("Unknown command");
            }
        }
    }

    /**
     * Displays the list of online users.
     */
    private void listUsers() {
        System.out.println("\nOnline users:");
        for (RoutingInfo r : routingTable) {
            System.out.print(r.username + " ");
        }
        if (routingTable.size() == 1) {
            System.out.println(" - You are alone :(");
        }
        System.out.println("\n");
    }

    /**
     * Sends a message to another user.
     */
    private void sendMessage() {
        System.out.print("\nTo: ");
        Scanner scanner = new Scanner(System.in);
        String username = scanner.nextLine();
        byte dst = getAddress(username);

        // Validate recipient username
        while (dst == -1 || Objects.equals(username, srcUsername)) {
            System.out.println("\nUsername not found, try again");
            if (Objects.equals(username, srcUsername)) {
                System.out.println("Are you trying to send messages to yourself?");
            }
            System.out.print("\nTo: ");
            username = scanner.nextLine();
            dst = getAddress(username);
        }

        byte nextHop = getNextHopAddress(username);
        System.out.println(nextHop);
        System.out.println("Message:\n");
        sending = true;
        try {
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read;
            read = System.in.read(temp.array());
            if (read < 1024) {
                sendPackets(read, temp, dst, nextHop, SRC);
            } else {
                System.out.println("Character limit 1024 exceeded");
            }
        } catch (InterruptedException | IOException e) {
            System.exit(2);
        }
        sending = false;
    }

    /**
     * Checks the user's inbox for incoming messages.
     */
    private void checkInbox() {
        System.out.println("\nInbox: ");
        incomingMessages();
        System.out.println();
    }

    /**
     * Displays help information about available commands.
     */
    private void displayHelp() {
        System.out.println("\nCommands:\n\nlist        shows the list of active users\n" +
                                   "message     opens the message menu\n" +
                                   "inbox       opens your inbox\n" +
                                   "quit        closes the chat\n" +
                                   "help        to see the messages above\n");
    }

    /**
     * Ends the chat session.
     */
    private void endChat() {
        System.out.println("Chat closed.");
        System.exit(0);
    }

    /**
     * Retrieves the current time in the format [HH:mm:ss].
     *
     * @return The current time formatted as [HH:mm:ss].
     */
    private static String getCurrentTime() {
        // Get current time
        LocalTime currentTime = LocalTime.now();

        // Define formatter for the time
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("[HH:mm:ss]");

        // Format the current time and return
        return currentTime.format(formatter);
    }

    public static void main(String args[]) throws InterruptedException {
        if (args.length > 0) { frequency = Integer.parseInt(args[0]); }
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void run() {
            while (true) {
                try {
                    Message m = receivedQueue.take();
                    if (m.getType() == MessageType.BUSY) { freeLink = false; }
                    else if (m.getType() == MessageType.FREE) { freeLink = true; }
                    else if (m.getType() == MessageType.DATA) {
                        freeLink = true;
                        // System.out.print("DATA: ");
                        // printByteBuffer (m.getData(), m.getData().capacity());
                        if (m.getData().get(1) == -1) {
                            routingUpdate(m.getData());
                        }
                        receivePackets(m.getData());
                    } else if (m.getType() == MessageType.DATA_SHORT) { freeLink = false; }
                    else if (m.getType() == MessageType.DONE_SENDING) {
                        freeLink = true;
                        // System.out.println("DONE_SENDING");
                    } else if (m.getType() == MessageType.HELLO) {
                        System.out.println("HELLO");
                    } else if (m.getType() == MessageType.END) {
                        System.out.println("END");
                        System.exit(0);
                    } else if (m.getType() == MessageType.TOKEN_ACCEPTED) {
                        System.out.println("Token Valid!");
                    } else if (m.getType() == MessageType.TOKEN_REJECTED) {
                        System.out.println("Token Rejected!");
                    }
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }

    /**
     * Sends packets containing the message to be transmitted.
     *
     * @param read The number of bytes read from the input.
     * @param temp The ByteBuffer containing the message to be sent.
     * @param dst  The destination address of the message.
     * @param frw  The forward address of the message.
     * @param src  The source address of the message.
     * @throws InterruptedException if the sending process is interrupted.
     */
    private void sendPackets(int read, ByteBuffer temp, byte dst, byte frw, byte src)
            throws InterruptedException {
        System.out.println();

        // Initialize new line offset
        int newLineOffset = 0;

        if (read > 0) {
            // Adjust new line offset
            if (temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r') {
                newLineOffset = 1;
            }
            if (read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r')) {
                newLineOffset = 2;
            }

            Message msg;
            int position = 0;
            int seq = 1 + new Random().nextInt(200);

            // Send packets in chunks of 26 bytes
            while (read > 26) {
                sentSeq = seq;
                seq++;
                ByteBuffer toSend = ByteBuffer.allocate(32);
                headerBuilder(toSend, src, dst, seq, frw, (frw != SRC),
                              false, false, false, 26);
                toSend.put(temp.array(), position, 26);
                msg = new Message(MessageType.DATA, toSend);
                stopAndWaitSend(msg);
                if (failedToSend) {
                    break;
                }
                position += 26;
                read -= 26;
            }

            // Send remaining bytes
            if (!failedToSend) {
                sentSeq = seq;
                seq++;
                ByteBuffer toSend = ByteBuffer.allocate(32);
                headerBuilder(toSend, src, dst, seq, frw, (frw != SRC), true, false, false, read);
                toSend.put(temp.array(), position, read - newLineOffset);
                msg = new Message(MessageType.DATA, toSend);
                stopAndWaitSend(msg);
                sentSeq = seq;
            }

            // Reset failure flag
            failedToSend = false;
        }
    }

    // TODO: Timeout adjustments

    private void stopAndWaitSend(Message msg) throws InterruptedException {
        int delay = 3000;
        // int totalTimeOut = 0;
        while (true) {
//            if (totalTimeOut == 30000) {
//                failedToSend = true;
//                break;
//            }
            if (delay == 3000) {
                delay = 0;
                sendPacketsHelper(msg);
            }
            if (receivedAck == sentSeq + 1) { break; }
            try { Thread.sleep(100); }
            catch (InterruptedException e) { e.printStackTrace(); }
            delay += 100;
            // totalTimeOut += 100;
        }
    }

    /**
     * Builds the header for the packet.
     *
     * @param packet      The ByteBuffer representing the packet.
     * @param source      The source address of the packet.
     * @param destination The destination address of the packet.
     * @param seq         The sequence number of the packet.
     * @param ack         The acknowledgment number of the packet.
     * @param FRW         Flag indicating forward status.
     * @param FIN         Flag indicating end of transmission.
     * @param RMS         Flag indicating retransmission request.
     * @param INIT        Flag indicating initialization.
     * @param length      The length of the packet payload.
     */
    private void headerBuilder(ByteBuffer packet, byte source, byte destination, int seq, int ack,
                               boolean FRW, boolean FIN, boolean RMS, boolean INIT,
                               int length) {
        packet.put(source); // Put source address
        packet.put(destination); // Put destination address
        packet.put((byte) seq); // Put sequence number
        packet.put((byte) ack); // Put acknowledgment number
        packet.put((byte) ((0) | (FRW ? 0b1000 : 0) | (INIT ? 0b100 : 0) | (FIN ? 0b10 : 0) |
                (RMS ? 0b01 : 0))); // Put flags
        packet.put((byte) length); // Put length
    }

    /**
     * Sends packets with collision avoidance mechanism.
     *
     * @param msg The message to be sent.
     * @throws InterruptedException if the sending process is interrupted.
     */
    private void sendPacketsHelper(Message msg) throws InterruptedException {
        // Collision avoidance (CA) implementation
        while (true) {
            if (freeLink) {
                sendingQueue.put(msg); // Put message in the sending queue
                try {
                    Thread.sleep(new Random().nextInt(200) + 500); // Random back off
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                break; // Exit loop after successful send
            }
            try {
                Thread.sleep(200); // Wait for 200 milliseconds (time slots of 1 second)
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Initializes the routing messages for each user in the routing table.
     */
    private void initRoutingMessage() {
        // Create a copy of the routing table to avoid concurrent modification
        List<RoutingInfo> copyOfRoutingTable = new ArrayList<>(routingTable);

        // Iterate through each routing info in the copy of the routing table
        for (RoutingInfo r : copyOfRoutingTable) {
            // Create a ByteBuffer for the routing message
            ByteBuffer routingMessage = ByteBuffer.allocate(32);

            // Build the header for the routing message
            headerBuilder(routingMessage, r.address, (byte) 255, 0, 0,
                          false, false, true, r.address == r.nextHopAddress,
                          r.username.length());

            // Put the username into the routing message ByteBuffer
            for (char c : r.username.toCharArray()) {
                routingMessage.put((byte) c);
            }

            // If the destination address is not the source address, put the source address in the routing message
            if (r.address != SRC) {
                routingMessage.put(SRC);
            }

            // Create a Message object with the routing message ByteBuffer and send it
            Message m = new Message(MessageType.DATA, routingMessage);
            try {
                sendPacketsHelper(m);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Thread responsible for sending routing messages periodically.
     */
    Thread routingMessage = new Thread(() -> {
        while (true) {
            // Check if source username is set and no message sending is ongoing
            if (!Objects.equals(srcUsername, "") && !sending) {
                initRoutingMessage(); // Initialize routing message
            }
            try {
                Thread.sleep(7000); // Sleep for 7 seconds
                Thread.sleep(new Random().nextInt(5000)); // Random additional sleep
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    });

    // TODO: Decide on the sleeptime for the full implementation

    Thread clearRoutingTable = new Thread(() -> {
        while (true) {
            try { Thread.sleep(120000); }
            catch (InterruptedException e) { e.printStackTrace(); }
            routingTable.clear();
            routingTable.add(new RoutingInfo(srcUsername, SRC, SRC));
        }
    });

    /**
     * Checks if a given address is present in the routing table.
     *
     * @param address The address to check.
     * @return True if the address is found in the routing table, false otherwise.
     */
    private boolean isInRoutingTable(byte address) {
        // Iterate through each RoutingInfo in the routing table
        for (RoutingInfo r : routingTable) {
            // Check if the address matches the current RoutingInfo's address
            if (r.address == address) {
                return true; // Address found, return true
            }
        }
        return false; // Address not found in the routing table, return false
    }

    /**
     * Updates the routing table based on the received routing message.
     *
     * @param packet The ByteBuffer containing the routing message.
     */
    private void routingUpdate(ByteBuffer packet) {
        // Create a new ByteBuffer for the routing message
        ByteBuffer routingMessage = ByteBuffer.allocate(32);

        // Initialize username
        String username = "";

        // Iterate through the packet content
        for (int i = 0; i < 6 + (int) packet.get(5); i++) {
            // Append characters after the fifth index to the username
            if (i > 5) {
                username += (char) packet.get(i);
            }

            // Copy packet content to the routing message ByteBuffer
            if (i == 4) {
                routingMessage.put((byte) 1);
            } else {
                routingMessage.put(packet.get(i));
            }
        }

        // Handle routing update based on packet type
        if (packet.get(4) == 5 && !isInRoutingTable(packet.get(0))) {
            // Add new entry to routing table for direct connection
            routingTable.add(new RoutingInfo(username, packet.get(0), SRC));
            routingMessage.put(SRC);
            Message msg = new Message(MessageType.DATA, routingMessage);
            try {
                sendPacketsHelper(msg);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } else if (packet.get(4) == 1 && !isInRoutingTable(packet.get(0))) {
            // Add new entry to routing table for indirect connection
            int ss = (int) packet.get(5) + 6;
            routingTable.add(new RoutingInfo(username, packet.get(0), packet.get(ss)));
            Message msg = new Message(MessageType.DATA, packet);
            try {
                sendPacketsHelper(msg);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Retrieves the username associated with the given address from the routing table.
     *
     * @param address The address for which to retrieve the username.
     * @return The username associated with the address, or "Unknown" if not found.
     */
    private String getUsername(byte address) {
        for (RoutingInfo r : routingTable) {
            if (r.address == address) {
                return r.username;
            }
        }
        return "Unknown";
    }

    /**
     * Retrieves the address associated with the given username from the routing table.
     *
     * @param username The username for which to retrieve the address.
     * @return The address associated with the username, or 255 if not found.
     */
    private byte getAddress(String username) {
        for (RoutingInfo r : routingTable) {
            if (Objects.equals(r.username, username)) {
                return r.address;
            }
        }
        return (byte) 255;
    }

    /**
     * Retrieves the next hop address associated with the given username from the routing table.
     *
     * @param username The username for which to retrieve the next hop address.
     * @return The next hop address associated with the username, or 255 if not found.
     */
    private byte getNextHopAddress(String username) {
        for (RoutingInfo r : routingTable) {
            if (Objects.equals(r.username, username)) {
                return r.nextHopAddress;
            }
        }
        return (byte) 255;
    }

    /**
     * Receives and processes incoming packets.
     *
     * @param packet The ByteBuffer containing the received packet.
     * @throws InterruptedException if the process is interrupted.
     */
    private void receivePackets(ByteBuffer packet) throws InterruptedException {
        // Check if the packet is from or destined to the current node
        if (packet.get(1) == SRC || packet.get(3) == SRC) {
            // Check if the packet is not an acknowledgment packet
            if (packet.get(2) != 0) {
                ByteBuffer ack = ByteBuffer.allocate(32);
                int tack = (packet.get(2) & 0xFF);
                byte dst = packet.get(0);

                // Adjust destination if needed
                if(packet.get(4) < 8 && packet.get(3) != (packet.get(0) & 0xFF)) {
                    dst = packet.get(3);
                }

                // Build acknowledgment packet header
                headerBuilder(ack, SRC, dst, 0, tack, false, false, false, false, 0);
                Message msg = new Message(MessageType.DATA, ack);
                sendPacketsHelper(msg);

                // Process incoming message
                if (!packetSet.contains(packet)) {
                    packetSet.add(packet);
                    int q = isInIncomingBuffer(packet.get(0), packet.get(2) & 0xFF);

                    // Process special message types
                    if (packet.get(4) == 2 || packet.get(4) == 10) {
                        if (q != -1) {
                            // Update existing incoming message
                            incomingBuffer.get(q).message = buildMessage(incomingBuffer.get(q).message, packet);
                            incomingBuffer.get(q).fullMessageArrived = true;
                            incomingBuffer.get(q).timeRecieved = getCurrentTime();

                            // Handle special message type 10
                            if (packet.get(4) == 10) {
                                qq = q;
                                qdst = packet.get(1);
                                if (!forwardMessage.isAlive()) {
                                    forwardMessage.start();
                                }
                            }
                        } else {
                            // Add new incoming message
                            incomingBuffer.add(new IncomingInfo(getUsername(packet.get(0)),
                                                                packet.get(0), (packet.get(2) & 0xFF) + 1, "",
                                                                (packet.get(4) == 10), true));
                            incomingBuffer.get(incomingBuffer.size() - 1).message = buildMessage(
                                    incomingBuffer.get(incomingBuffer.size() - 1).message, packet);
                            incomingBuffer.get(incomingBuffer.size() - 1).timeRecieved = getCurrentTime();
                            q = incomingBuffer.size() - 1;
                            if (packet.get(4) == 10) {
                                qq = q;
                                qdst = packet.get(1);
                                if (!forwardMessage.isAlive()) {
                                    forwardMessage.start();
                                }
                            }
                        }
                        sending = false;
                    } else if (packet.get(4) == 0 || packet.get(4) == 8) {
                        // Process regular data packets
                        sending = true;
                        if (q != -1) {
                            incomingBuffer.get(q).message = buildMessage(incomingBuffer.get(q).message, packet);
                            incomingBuffer.get(q).seq++;
                        } else {
                            incomingBuffer.add(new IncomingInfo(getUsername(packet.get(0)),
                                                                packet.get(0), (packet.get(2) & 0xFF) + 1, "",
                                                                (packet.get(4) == 8), false));
                            incomingBuffer.get(incomingBuffer.size() - 1).message = buildMessage(
                                    incomingBuffer.get(incomingBuffer.size() - 1).message, packet);
                        }
                    }
                }
            } else {
                receivedAck = (packet.get(3) & 0xFF);
            }
        }
    }

    // Variables to store information about the message to be forwarded
    private int qq;
    private byte qdst;

    /**
     * Thread responsible for forwarding a message to its next hop.
     */
    Thread forwardMessage = new Thread(() -> {
        // Create a ByteBuffer to hold the forwarded message
        ByteBuffer forwardedMessage = ByteBuffer.allocate(1024);

        // Copy the message to the ByteBuffer
        forwardedMessage.put(incomingBuffer.get(qq).message.getBytes());

        // Retrieve the next hop address and source address for forwarding
        byte frw = getNextHopAddress(incomingBuffer.get(qq).username);
        byte src = getAddress(incomingBuffer.get(qq).username);

        // Send the message to the next hop
        try {
            sendPackets(incomingBuffer.get(qq).message.length(), forwardedMessage, qdst, frw, src);
        } catch (InterruptedException e) {
            // Convert InterruptedException to RuntimeException
            throw new RuntimeException(e);
        }
    });

    /**
     * Checks if a message with the given address and sequence number is in the incoming buffer.
     *
     * @param address The address of the message.
     * @param seq     The sequence number of the message.
     * @return The index of the message in the buffer, or -1 if not found.
     */
    private int isInIncomingBuffer(byte address, int seq) {
        for (int i = 0; i < incomingBuffer.size(); i++) {
            if (incomingBuffer.get(i).address == address && incomingBuffer.get(i).seq == seq) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Processes incoming messages in the inbox.
     */
    private void incomingMessages() {
        System.out.println();
        if (incomingBuffer.isEmpty()) {
            System.out.println("Inbox empty");
        }
        Iterator<IncomingInfo> iterator = incomingBuffer.iterator();
        while (iterator.hasNext()) {
            IncomingInfo i = iterator.next();
            if (i.fullMessageArrived && !i.toBeForwarded) {
                // Trim the last character of the message
                i.message = i.message.substring(0, i.message.length() - 1);
                System.out.println(i.timeRecieved + " " + i.username + ": " + i.message);
                iterator.remove(); // Remove the processed message from the buffer
            }
        }
    }

    /**
     * Builds the message by appending characters from the packet.
     *
     * @param message The current message content.
     * @param packet  The ByteBuffer containing the packet.
     * @return The updated message after appending characters from the packet.
     */
    private String buildMessage(String message, ByteBuffer packet) {
        // Create a StringBuilder to manipulate the message
        StringBuilder messageBuilder = new StringBuilder(message);

        // Append characters from the packet to the message
        for (int i = 6; i < 6 + packet.get(5); i++) {
            messageBuilder.append((char) packet.get(i));
        }

        // Convert StringBuilder back to String and return
        return messageBuilder.toString();
    }

    /* DEBUGGING TOOLS */

    /**
     * Prints the content of a ByteBuffer.
     *
     * @param bytes       The ByteBuffer to print.
     * @param bytesLength The length of the ByteBuffer.
     */
    private void printByteBuffer(ByteBuffer bytes, int bytesLength) {
        System.out.print("[" + getCurrentTime() + "] ");

        // Print the first 6 bytes as integers
        for (int j = 0; j < 6; j++) {
            byte charByte = bytes.get(j);
            System.out.print((int) charByte + " ");
        }

        // Print the rest of the bytes as characters
        for (int i = 6; i < bytesLength; i++) {
            byte charByte = bytes.get(i);
            System.out.print((char) charByte + "");
        }
        System.out.println();
    }

    /**
     * Prints the content of the incoming message buffer.
     */
    private void printIncomingBuffer() {
        for (IncomingInfo i : incomingBuffer) {
            System.out.print(i.username + " ");
            System.out.print(i.address + " ");
            System.out.print(i.seq + " ");
            System.out.print(i.message + " ");
            System.out.print(i.toBeForwarded + " ");
            System.out.println(i.fullMessageArrived);
        }
        System.out.println();
    }

    /**
     * Prints the content of the routing table.
     */
    private void printRoutingTable() {
        for (RoutingInfo r : routingTable) {
            System.out.print(r.username + " ");
            System.out.print(r.address + " ");
            System.out.println(r.nextHopAddress);
        }
        System.out.println();
    }
}