package ist.depchain.core.hotstuff;

import java.util.LinkedList;
import java.util.Queue;

import ist.depchain.common.Command;

public class BasicHotStuffTree {
    private final Queue<Command> commandsTree = new LinkedList<>();

    public void addCommand(Command command) {
        commandsTree.offer(command);
    }

    public Command getNextCommand() {
        return commandsTree.poll();
    }
}
