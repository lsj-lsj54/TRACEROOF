# 后端镜像：多阶段构建，产出可直接 java -jar 运行的 fat jar
#
# 注意 pom.xml 里 spring-boot-maven-plugin 的 skip 必须为 false（已修正），
# 否则打出来的是不含依赖的普通 jar，启动会报「没有主清单属性」。

# ---------- 构建阶段 ----------
FROM maven:3.8.8-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷 pom，利用镜像层缓存把依赖提前拉下来，改代码时不用重下
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests || true

COPY src ./src

# 双保险：即使有人在本机（有 application-local.yml）构建镜像，
# 也先把这个含真实密码与腾讯云密钥的文件删掉，避免被打进镜像层。
# 生产环境的配置一律走环境变量注入（见 deploy/docker-compose.yml）。
RUN rm -f src/main/resources/application-local.yml

RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 时区按需调整，容器默认是 UTC，日志时间会对不上
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

COPY --from=build /build/target/*.jar app.jar

# JVM 参数：容器内按比例分配堆，2G 内存的机器也能跑得比较稳
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -Dfile.encoding=UTF-8"

EXPOSE 8666

# 用 exec 形式让 java 成为 1 号进程，能正确接收 docker stop 的信号
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
