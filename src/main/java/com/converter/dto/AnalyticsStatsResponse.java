package com.converter.dto;

public class AnalyticsStatsResponse {
    private String status;
    private Data data;

    public AnalyticsStatsResponse() {
        this.status = "success";
    }

    public AnalyticsStatsResponse(long totalVisits, long totalConversions, long uniqueVisitors, double conversionRate) {
        this.status = "success";
        this.data = new Data(totalVisits, totalConversions, uniqueVisitors, conversionRate);
    }

    // Getters and Setters
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Data getData() {
        return data;
    }

    public void setData(Data data) {
        this.data = data;
    }

    public static class Data {
        private long totalVisits;
        private long totalConversions;
        private long uniqueVisitors;
        private double conversionRate;

        public Data() {}

        public Data(long totalVisits, long totalConversions, long uniqueVisitors, double conversionRate) {
            this.totalVisits = totalVisits;
            this.totalConversions = totalConversions;
            this.uniqueVisitors = uniqueVisitors;
            this.conversionRate = conversionRate;
        }

        // Getters and Setters
        public long getTotalVisits() {
            return totalVisits;
        }

        public void setTotalVisits(long totalVisits) {
            this.totalVisits = totalVisits;
        }

        public long getTotalConversions() {
            return totalConversions;
        }

        public void setTotalConversions(long totalConversions) {
            this.totalConversions = totalConversions;
        }

        public long getUniqueVisitors() {
            return uniqueVisitors;
        }

        public void setUniqueVisitors(long uniqueVisitors) {
            this.uniqueVisitors = uniqueVisitors;
        }

        public double getConversionRate() {
            return conversionRate;
        }

        public void setConversionRate(double conversionRate) {
            this.conversionRate = conversionRate;
        }
    }
}