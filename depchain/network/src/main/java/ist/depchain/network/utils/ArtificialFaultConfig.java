package ist.depchain.network.utils;

public class ArtificialFaultConfig {
    
    private double dropProbability; 
    private double duplicateProbability; 
    private int maxDelayMs; 

    public ArtificialFaultConfig(double dropProbability, double duplicateProbability, int maxDelayMs) {
        this.dropProbability = dropProbability;
        this.duplicateProbability = duplicateProbability;
        this.maxDelayMs = maxDelayMs;
    }

    public double getDropProbability() {
        return dropProbability;
    }

    public double getDuplicateProbability() {
        return duplicateProbability;
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }
}
