package ist.depchain.core;

public class CommandExecutor {
    private final BlockChain blockChain;

    public CommandExecutor(BlockChain blockChain) {
        this.blockChain = blockChain;
    }

    public void executeCommand(String commandType, String data) {
        switch (commandType) {
            case "append":
                blockChain.append(data);
                break;
            case "show_log":
                blockChain.showLog();
                break;
            default:
                System.err.println("[COMMAND_EXECUTOR | ERROR] - Unknown command type: " + commandType);
        }
    }
}
