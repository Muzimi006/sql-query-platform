# 运行镜像：只装 JRE。
#
# ⚠️ 这个文件**只负责跑**，不负责构建：jar 由 CI / 本机 mvn package 生成后 COPY 进来
#    （见 docker-compose.yml 的 app 服务与 README 的构建步骤）。
#    为什么不在镜像里构建：那会需要一个带 Maven 的构建阶段，每次构建都要重新下载依赖，
#    而本项目已经有 CI 在做这件事 —— 镜像只装运行期需要的东西，攻击面也更小。
FROM eclipse-temurin:17-jre

# HEALTHCHECK 要用到 curl，而 JRE 基础镜像不保证带它 —— 显式装上，别依赖运气。
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# ⚠️ 非 root 运行：
#    容器里的进程默认是 root，而「逃逸」类漏洞几乎都以「进程已经是 root」为前提。
#    这里建一个无登录权限的系统用户，容器里的一切都以它的身份执行。
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app

# --chown：拷进来的 jar 直接属于 app，省掉一次递归 chown
COPY --chown=app:app target/sql-query-platform-0.0.1-SNAPSHOT.jar app.jar

# 导出文件目录（export.file-* 配置写的就是这个路径）。
# 先建好并交给非 root 用户，否则导出任务会以「没有写权限」失败 ——
# 而这种失败在本地用 root 跑是**复现不出来**的。
RUN mkdir -p /app/exports && chown -R app:app /app/exports

USER app

# 8080 = 业务端口；8081 = 监控端口（默认只监听 127.0.0.1，容器内自检够用）
EXPOSE 8080 8081

# 存活探针走 actuator 的 liveness 组（见 application.yml：liveness 只看 ping，
# 刻意不包含 Redis / RabbitMQ —— 它们挂了本项目是降级可用，不该被判成「要重启」）。
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health/liveness || exit 1

# ⚠️ 两条 JVM 参数都不是装饰：
#    -XX:MaxRAMPercentage=75 —— 容器里不写它，JVM 会按**宿主机**内存算堆，
#      在有内存限额的编排环境里会被 OOMKill 掉（现象是「莫名其妙被杀」）。
#    -XX:+ExitOnOutOfMemoryError —— OOM 之后尽快退出让编排重启，
#      而不是留一个「还在跑但什么都做不了」的进程。
# 用 exec 形式（JSON 数组）：进程直接是 PID 1，SIGTERM 能直达 JVM，优雅停机才生效。
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

