# -*- coding: utf-8 -*-
import xml.etree.ElementTree as ET
import sys
from xml.dom import minidom

def prettify(elem):
    """将 XML 节点转换为带缩进的优美格式"""
    rough_string = ET.tostring(elem, 'utf-8')
    reparsed = minidom.parseString(rough_string)
    # 去除多余的空行
    return '\n'.join([line for line in reparsed.toprettyxml(indent="    ").split('\n') if line.strip()])

def convert_xml(input_file, output_file, db_type="opengauss"):
    print(f"[*] 正在读取旧版 XML 配置文件: {input_file}")
    tree = ET.parse(input_file)
    root = tree.getroot()

    # 类名到 DBChaos 故障关键字的映射
    class_map = {
        "StackOverflowTest": "stack_overflow",
        "MassiveRollbackTest": "massive_rollback",
        "MaxConnectionTest": "max_connection",
        "UncommittedTransactionFaultInject": "uncommitted_txn",
        "MaxPreparedTransactionFaultInject": "max_prepared",
        "DuplicateTransactionFaultInject": "duplicate_txn",
        "MaxMemTest": "memory_pressure"
    }

    jar_path = "/home/hancz/agent-2024-SNAPSHOT/scripts/DBChaos-0.0.1.jar"
    converted_count = 0

    for case in root.findall('.//case'):
        injection = case.find('injection')
        if injection is None: 
            continue

        # 提取旧配置中的所有参数
        args = [arg.text for arg in injection.findall('arg')]
        old_class = None
        
        # 寻找命中的旧版注入类名
        for cls in class_map.keys():
            if cls in args:
                old_class = cls
                break

        if old_class:
            fault_cmd = class_map[old_class]
            new_args = []
            
            # 定位到类名后的参数列表进行解析和翻译
            i = args.index(old_class) + 1
            while i < len(args):
                key = args[i]
                val = args[i+1] if i+1 < len(args) else None

                # ---- 智能参数翻译逻辑 ----
                if old_class == "StackOverflowTest" and key == "-type":
                    new_args.extend(["-mode", val])
                elif old_class == "MassiveRollbackTest" and key == "-duration":
                    new_args.extend(["-duration", str(int(val) * 1000)]) # 秒转毫秒
                elif old_class == "UncommittedTransactionFaultInject":
                    if key == "-lockHoldDuration":
                        new_args.extend(["-duration", str(int(val) * 1000)])
                    elif key == "-lockHoldingSessions":
                        new_args.extend(["-holders", val])
                    elif key == "-lockRowsCount":
                        new_args.extend(["-rows", val])
                elif old_class == "MaxPreparedTransactionFaultInject":
                    if key == "-target-prepared-count":
                        new_args.extend(["-count", val])
                    elif key == "-client-concurrency":
                        new_args.extend(["-concurrency", val])
                    elif key == "-hold-duration-sec":
                        new_args.extend(["-duration", str(int(val) * 1000)])
                elif old_class == "DuplicateTransactionFaultInject":
                    if key == "-concurrentSessions":
                        new_args.extend(["-sessions", val])
                    elif key == "-lockHoldDuration":
                        new_args.extend(["-duration", str(int(val) * 1000)])
                else:
                    # 保留未改变名称的参数 (如 -threads, -rate 等)
                    if val is not None:
                        new_args.extend([key, val])
                i += 2

            # 补充新版必填的缺省参数
            if fault_cmd == "uncommitted_txn" and "-table" not in new_args:
                new_args.extend(["-table", "bmsql_stock"]) # TPC-C 的核心表

            # --------------------------
            # 1. 重构 <injection> 节点
            # --------------------------
            injection.clear()
            cmd_elem = ET.SubElement(injection, 'cmd')
            cmd_elem.text = "java"

            # 组装 DBChaos 标准命令：java -jar DBChaos.jar <DB_TYPE> <FAULT_TYPE> [OPTIONS]
            new_elements = ["-jar", jar_path, db_type, fault_cmd] + new_args
            for arg_text in new_elements:
                arg_elem = ET.SubElement(injection, 'arg')
                arg_elem.text = str(arg_text)

            # --------------------------
            # 2. 重构 <recovery> 节点
            # --------------------------
            recovery = case.find('recovery')
            if recovery is not None:
                recovery.clear()
                rcmd = ET.SubElement(recovery, 'cmd')
                rcmd.text = "pkill"
                rarg1 = ET.SubElement(recovery, 'arg')
                rarg1.text = "-f"
                rarg2 = ET.SubElement(recovery, 'arg')
                rarg2.text = "DBChaos-0.0.1.jar"
            
            converted_count += 1
            print(f"  -> 已转换 Case ID: {case.find('id').text} [{old_class} -> {fault_cmd}]")

    # 写入新文件
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(prettify(root))
    
    print(f"[*] 转换完成！共升级了 {converted_count} 个故障注入用例。")
    print(f"[*] 新的 XML 已保存至: {output_file}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("用法: python convert_cases.py <旧版XML路径> <输出XML路径>")
        sys.exit(1)
        
    input_xml = sys.argv[1]
    output_xml = sys.argv[2]
    convert_xml(input_xml, output_xml)