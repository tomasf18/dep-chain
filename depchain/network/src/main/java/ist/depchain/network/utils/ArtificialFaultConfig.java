package ist.depchain.network.utils;

public class ArtificialFaultConfig {

    private double dropProbability;
    private double duplicateProbability;
    private double tamperProbability;
    private int maxDelayMs;

    public ArtificialFaultConfig(double dropProbability, double duplicateProbability, double tamperProbability, int maxDelayMs) {
        this.dropProbability = dropProbability;
        this.duplicateProbability = duplicateProbability;
        this.tamperProbability = tamperProbability;
        this.maxDelayMs = maxDelayMs;
    }

    public double getDropProbability() {
        return dropProbability;
    }

    public double getDuplicateProbability() {
        return duplicateProbability;
    }

    public double getTamperProbability() {
        return tamperProbability;
    }

    public int getMaxDelayMs() {
        return maxDelayMs;
    }
}
