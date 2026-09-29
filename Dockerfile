FROM ubuntu:24.04

ARG UBUNTU_MIRROR=http://mirrors.aliyun.com/ubuntu
ARG OPENJDK_PACKAGE_VERSION=17.0.20.1+1-1~24.04
ARG ANDROID_CMDLINE_TOOLS_VERSION=15859902
ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

RUN set -eux; \
    sed -i \
        -e "s|http://archive.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        -e "s|http://security.ubuntu.com/ubuntu|${UBUNTU_MIRROR}|g" \
        /etc/apt/sources.list.d/ubuntu.sources; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        lib32stdc++6 \
        lib32z1 \
        libc6-i386 \
        unzip; \
    jre_package="openjdk-17-jre-headless_${OPENJDK_PACKAGE_VERSION}_amd64.deb"; \
    jdk_package="openjdk-17-jdk-headless_${OPENJDK_PACKAGE_VERSION}_amd64.deb"; \
    jre_sha256="$(apt-cache show "openjdk-17-jre-headless=${OPENJDK_PACKAGE_VERSION}" | awk '/^SHA256:/{print $2; exit}')"; \
    jdk_sha256="$(apt-cache show "openjdk-17-jdk-headless=${OPENJDK_PACKAGE_VERSION}" | awk '/^SHA256:/{print $2; exit}')"; \
    test -n "${jre_sha256}"; \
    test -n "${jdk_sha256}"; \
    curl --fail --location --silent --show-error --retry 3 \
        "${UBUNTU_MIRROR}/pool/main/o/openjdk-17/${jre_package}" \
        --output "/tmp/${jre_package}"; \
    curl --fail --location --silent --show-error --retry 3 \
        "${UBUNTU_MIRROR}/pool/main/o/openjdk-17/${jdk_package}" \
        --output "/tmp/${jdk_package}"; \
    printf '%s  %s\n' "${jre_sha256}" "/tmp/${jre_package}" | sha256sum --check --status; \
    printf '%s  %s\n' "${jdk_sha256}" "/tmp/${jdk_package}" | sha256sum --check --status; \
    mv "/tmp/${jre_package}" /var/cache/apt/archives/; \
    mv "/tmp/${jdk_package}" /var/cache/apt/archives/; \
    apt-get install -y --no-install-recommends \
        "openjdk-17-jdk-headless=${OPENJDK_PACKAGE_VERSION}"; \
    rm -rf /var/lib/apt/lists/*; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools" /tmp/android-cmdline-tools; \
    curl --fail --location --silent --show-error \
        "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" \
        --output /tmp/android-command-line-tools.zip; \
    echo "${ANDROID_CMDLINE_TOOLS_SHA256}  /tmp/android-command-line-tools.zip" | sha256sum --check --status; \
    unzip -q /tmp/android-command-line-tools.zip -d /tmp/android-cmdline-tools; \
    mv /tmp/android-cmdline-tools/cmdline-tools "${ANDROID_HOME}/cmdline-tools/latest"; \
    yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses >/dev/null; \
    sdkmanager --sdk_root="${ANDROID_HOME}" --install \
        "platform-tools" \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "ndk;27.0.12077973" \
        "cmake;3.22.1"; \
    rm -rf /tmp/android-cmdline-tools /tmp/android-command-line-tools.zip; \
    apt-get clean

RUN JAVA_TOOL_OPTIONS="-Dhttps.protocols=TLSv1.3,TLSv1.2 -Djdk.tls.client.protocols=TLSv1.3,TLSv1.2" \
    sdkmanager --sdk_root="${ANDROID_HOME}" --install "build-tools;35.0.0"

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

WORKDIR /workspace

CMD ["sleep", "infinity"]
