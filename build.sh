#!/bin/bash

# 默认 JAR 名称
DEFAULT_JAR_NAME="resilienceChaos-1.0"

# 获取输入参数作为 JAR 名称
JAR_NAME=${1:-$DEFAULT_JAR_NAME}

echo "============================================"
echo " 开始编译并打包项目: $JAR_NAME "
echo "============================================"

# 执行 Maven 打包，并传入自定义名称变量
mvn clean package -Djar.name=$JAR_NAME -DskipTests

if [ $? -eq 0 ]; then
    echo "--------------------------------------------"
    echo " 打包成功！"
    echo " 文件路径: target/$JAR_NAME.jar"
    echo "--------------------------------------------"
    
    # 将包拷贝到根目录方便运行
    cp target/$JAR_NAME.jar ./$JAR_NAME.jar
    echo " 已将包拷贝至根目录: ./$JAR_NAME.jar"
else
    echo " 打包失败，请检查 Maven 日志。"
    exit 1
fi