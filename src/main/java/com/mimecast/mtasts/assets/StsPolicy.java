package com.mimecast.mtasts.assets;

import com.mimecast.mtasts.cache.PolicyCache;
import com.mimecast.mtasts.client.HttpsResponse;
import com.mimecast.mtasts.config.Config;
import com.mimecast.mtasts.config.ConfigHandler;
import com.mimecast.mtasts.stream.LineInputStream;
import com.mimecast.mtasts.util.Pair;
import org.apache.commons.validator.routines.DomainValidator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidParameterException;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict Transport Security Policy.
 * <p>Parser for MTA-STS HTTPS policy file contents.
 * <p>Once constructed all data can be retrieved.
 * <p>Primary scope is to match MX domains against the policy list of MX masks.
 *
 * @link https://tools.ietf.org/html/rfc8461#section-3.2 RFC8461#section-3.2
 *
 * @see StsRecord
 * @see PolicyCache
 * @author "Vlad Marian" <vmarian@mimecast.com>
 * @link http://mimecast.com Mimecast
 */
public final class StsPolicy extends ConfigHandler {
    private static final Logger log = LogManager.getLogger(StsPolicy.class);

    /**
     * MTA-STS record instance.
     */
    private StsRecord record;

    /**
     * TLSRPT record instance.
     */
    private StsReport report;

    /**
     * Policy string.
     */
    private String policy;

    /**
     * Response instance.
     */
    private HttpsResponse response;

    /**
     * StsPolicyValidator instance.
     */
    private final StsPolicyValidator validator = new StsPolicyValidator();

    /**
     * Certificates chain list.
     */
    private List<Certificate> certificates;

    /**
     * Version string.
     */
    private String version;

    /**
     * Mode enum.
     */
    private StsMode mode = StsMode.NONE;

    /**
     * MX masks list.
     */
    private final List<String> mxMasks = new ArrayList<>();

    /**
     * Max age integer.
     */
    private int maxAge;

    /**
     * Fetch time long.
     */
    private long fetchTime = 0L;

    /**
     * Cached boolean.
     */
    private boolean cached;

    /**
     * Constructs a new StsPolicy instance with given record and HTTP response.
     * <p>Requires a fresh StsRecord instance to construct so the pair can be cached together.
     * <p>While the record isn't used within it is required for update check.
     * <p>Whenever the policy is to be used a new record should be fetched to ensure the policy was not updated by comparing cached and new record IDs.
     * <p>The parser will not except on parsing so it should always be validated via the provided isValid() method.
     *
     * @param record StsRecord instance.
     * @param response HttpsResponse instance.
     */
    public StsPolicy(StsRecord record, HttpsResponse response) {
        this.record = record;
        this.response = response;
        this.fetchTime = Instant.now().getEpochSecond();
    }

    /**
     * Constructs a new StsPolicy instance with given extendedPolicy only.
     * <p>Designed for constructing from an extended extendedPolicy generated by asString().
     * <p>This helps retrieve both objects from a single string.
     *
     * @param extendedPolicy Extended policy string.
     */
    public StsPolicy(String extendedPolicy) {
        this.policy = extendedPolicy;
    }

    /**
     * Sets config.
     *
     * @param config Config instance.
     * @return Self.
     */
    @Override
    public StsPolicy setConfig(Config config) {
        super.setConfig(config);
        return this;
    }

    /**
     * Make policy.
     *
     * @return Self.
     */
    public StsPolicy make() {
        // Validate HTTP response and policy body.
        if (response != null) {
            this.policy = validator.getPolicy(response, config);

            try {
                certificates = response.getPeerCertificates();
            } catch (Exception e) {
                log.error("Handshake certificate chain not found");
            }
        }

        // Make.
        if (policy != null) {
            List<Pair> pairs = parse();
            makePolicy(pairs);

            if (record == null) {
                makeRecord(pairs);
            }
        }

        return this;
    }

    /**
     * Parse data.
     *
     * @return List of Pairs of String, String.
     */
    private List<Pair> parse() {
        List<Pair> pairs = new ArrayList<>();

        try (LineInputStream br = new LineInputStream(new ByteArrayInputStream(policy.getBytes(StandardCharsets.UTF_8)))) {
            byte[] line;
            while ((line = br.readLine()) != null) {
                validator.validateLine(line, config);

                Pair pair = new Pair(new String(line));
                if (pair.isValid()) {
                    pairs.add(pair);
                }
            }
        } catch (IOException e) {
            log.error("Unable to read policy lines: {}", e.getMessage());
        }

        return pairs;
    }

    /**
     * Makes policy.
     * <p>Sets instance variables.
     * <p>Enforces a soft MIN limit for max age of 86400 for none or testing mode.
     * <p>Enforces a hard MIN limit for max age of 604800 for enforced mode.
     * <p>Enforces a hard MAX limit for max age of 31557600.
     *
     * @param pairs List of Pair of String, String.
     */
    private void makePolicy(List<Pair> pairs) {
        for (Pair entry : pairs) {
            switch (entry.getKey()) {
                case "version":
                    version = entry.getValue();
                    break;

                case "mode":
                    mode = StsMode.get(entry.getValue()).orElse(StsMode.NONE);
                    break;

                case "mx":
                    mxMasks.add(entry.getValue());
                    break;

                case "max_age":
                    try {
                        maxAge = Integer.parseInt(entry.getValue());
                    } catch (NumberFormatException e) {
                        log.error("max_age: '{}' is in wrong format. Default policy min age is being used", entry.getValue());
                        maxAge = config.getPolicyMinAge();
                    }
                    if (maxAge > config.getPolicyMaxAge()) {
                        validator.addWarning("Max age more than config max: " +  maxAge + " > " + config.getPolicyMaxAge());
                        maxAge = Math.min(maxAge, config.getPolicyMaxAge());
                    }
                    break;
                default:
                    break;
            }
        }

        // Limitations.
        if (mode == StsMode.ENFORCE && maxAge < config.getPolicyMinAge()) {
            validator.addWarning("Max age less than config min: " +  maxAge + " < " + config.getPolicyMinAge());
            maxAge = Math.max(maxAge, config.getPolicyMinAge());
        }
        else if(mode == StsMode.TESTING && maxAge < config.getPolicySoftMinAge()) {
            validator.addWarning("Max age less than config soft min: " +  maxAge + " < " + config.getPolicySoftMinAge());
            maxAge = Math.max(maxAge, config.getPolicySoftMinAge());
        }
    }

    /**
     * Makes record.
     * <p>Constructs a new StsRecord instance and sets it in this policy instance variable.
     *
     * @param pairs List of Pair of String, String.
     */
    private void makeRecord(List<Pair> pairs) {
        String domain = null;
        String recordId = null;

        for (Pair entry : pairs) {
            switch (entry.getKey()) {
                case "fetch_time":
                    try {
                        fetchTime = Long.parseLong(entry.getValue());
                    } catch (NumberFormatException e) {
                        log.error("Policy fetch_time invalid");
                    }
                    break;

                case "record_id":
                    if (entry.getValue().trim().length() > 0) {
                        recordId = entry.getValue();
                    }
                    break;

                case "domain":
                    if (DomainValidator.getInstance(false).isValid(entry.getValue())) {
                        domain = entry.getValue();
                    }
                    break;
                default:
                    break;
            }
        }

        // Create record if domain, record_id and fetch_time set.
        if (domain != null && recordId != null && fetchTime > 0) {
            record = new StsRecord(domain, "v=" + version + "; id=" + recordId + ";");
        }
        // Throw runtime exception.
        else {
            throw new InvalidParameterException("Record missing domain, record_id and/or fetch_time");
        }
    }

    /**
     * Gets peer certificates.
     *
     * @return List of Certificate instances.
     */
    public List<Certificate> getPeerCertificates() {
        return certificates;
    }

    /**
     * Gets record.
     * <p>This should be the DNS record at the time of policy fetching.
     *
     * @return StsRecord instance.
     */
    public StsRecord getRecord() {
        return record;
    }

    /**
     * Sets report.
     * <p>This should be the DNS record at the time of policy fetching.
     *
     * @param report StsReport instance.
     * @return Self.
     */
    public StsPolicy setReport(StsReport report) {
        this.report = report;
        return this;
    }

    /**
     * Gets report.
     *
     * @return StsRecord instance.
     */
    public StsReport getReport() {
        return report;
    }

    /**
     * Gets policy.
     * <p>Raw policy string.
     *
     * @return Policy string.
     */
    public String getPolicy() {
        return policy;
    }

    /**
     * Gets policy validator.
     *
     * @return Policy string.
     */
    public StsPolicyValidator getValidator() {
        return validator;
    }

    /**
     * Is valid.
     * <p>Is mode is not null or NONE?
     * <p>Is max age defined and greater than 0?
     * <p>Is at least on MX mask present?
     *
     * @return Boolean.
     */
    public boolean isValid() {
        return validator.getErrors().isEmpty() && mode != StsMode.NONE && maxAge > 0 && !mxMasks.isEmpty();
    }

    /**
     * Is expired.
     * <p>Has max age passed since fetch time?
     *
     * @return Boolean.
     */
    public boolean isExpired() {
        return fetchTime + maxAge <= Instant.now().getEpochSecond();
    }

    /**
     * Match MX.
     * <p>Masks are evaluated by a relaxed regex match.
     *
     * @param mx MX domain string.
     * @return Boolean.
     */
    public boolean matchMx(String mx) {
        if (mode.equals(StsMode.TESTING)) {
            return true;
        }

        for (String mask : mxMasks) {
            if (mx.matches(mask.replace("*", ".*"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets version.
     *
     * @return Version string.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Gets mode.
     *
     * @return Mode enum.
     */
    public StsMode getMode() {
        return mode;
    }

    /**
     * Gets MX masks.
     *
     * @return List of String.
     */
    public List<String> getMxMasks() {
        return mxMasks;
    }

    /**
     * Gets max age.
     *
     * @return Max age integer.
     */
    public int getMaxAge() {
        return maxAge;
    }

    /**
     * Gets fetch time.
     * <p>This is set on construction unless using string only constructor.
     *
     * @return Fetch time integer.
     */
    public long getFetchTime() {
        return fetchTime;
    }

    /**
     * Sets cache.
     *
     * @param cached Boolean.
     */
    public void setCached(boolean cached) {
        this.cached = cached;
    }

    /**
     * Is cached.
     * <p>For testing.
     *
     * @return Cache boolean.
     */
    public boolean isCached() {
        return cached;
    }

    /**
     * To string.
     *
     * @return Policy string.
     */
    public String toString() {
        return policy;
    }

    /**
     * As string.
     * <p>Returns an extended policy string for caching.
     *
     * @return Extended policy string.
     */
    public String asString() {
        StringBuilder builder = new StringBuilder()
                .append("version: ").append(version).append("\r\n")
                .append("mode: ").append(mode).append("\r\n");

        for (String mask : mxMasks) {
            builder.append("mx: ").append(mask).append("\r\n");
        }

        builder.append("max_age: ").append(maxAge).append("\r\n")
            .append("fetch_time: ").append(fetchTime).append("\r\n")
            .append("domain: ").append(record.getDomain()).append("\r\n")
            .append("record_id: ").append(record.getId()).append("\r\n");

        return builder.toString();
    }
}
