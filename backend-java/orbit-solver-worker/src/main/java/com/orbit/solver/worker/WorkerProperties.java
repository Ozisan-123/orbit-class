package com.orbit.solver.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * solver-worker 的配置（P3）。
 *
 * <p>⚠ 这个进程**不连数据库**（方案 §2.2：solver-worker「拥有的表：无」）。
 * 它需要的一切——任务身份、冻结的输入快照、当时那套规则——都由
 * scheduling 的 {@code POST /internal/jobs/claim} 一次性下发。
 * 所以它的配置里只有"怎么找到 scheduling"与"我自己是谁"，没有数据源。
 */
@ConfigurationProperties(prefix = "orbit.worker")
public class WorkerProperties {

    /**
     * scheduling 的地址。
     *
     * <p>⚠ P3 还没有网关，所以这里直连 scheduling（8083）。
     * 上了网关之后应改成网关地址，并走内部路由。
     */
    private String schedulingBaseUrl = "http://127.0.0.1:8083";

    /** 与 scheduling 约定的共享密钥（对应 {@code InternalJobController} 的校验）。 */
    private String secret = "orbit-dev-worker";

    /**
     * worker 自己的名字。
     *
     * <p>它会写进 {@code schedule_jobs.worker_id} 与事件流 —— 多副本部署时，
     * 「这一单是谁做的 / 谁被接管了」全靠它区分。所以默认值带进程号，
     * **不能是固定字符串**（否则两个副本会互相冒充）。
     */
    private String id = "worker-" + ProcessHandle.current().pid();

    /** 本进程最多同时求解几个（真正的全局上限还是 scheduling 侧的容量槽位）。 */
    private int concurrency = 1;

    /** 没活干时多久轮询一次（毫秒）。 */
    private long pollIntervalMs = 2000;

    /** 单次求解的时间上限（秒），对应 Python 的 {@code time_limit=60}。 */
    private int timeLimitSeconds = 60;

    /** CP-SAT 的搜索线程数。 */
    private int numSearchWorkers = 2;

    public String getSchedulingBaseUrl() {
        return schedulingBaseUrl;
    }

    public void setSchedulingBaseUrl(String schedulingBaseUrl) {
        this.schedulingBaseUrl = schedulingBaseUrl;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public void setTimeLimitSeconds(int timeLimitSeconds) {
        this.timeLimitSeconds = timeLimitSeconds;
    }

    public int getNumSearchWorkers() {
        return numSearchWorkers;
    }

    public void setNumSearchWorkers(int numSearchWorkers) {
        this.numSearchWorkers = numSearchWorkers;
    }
}
