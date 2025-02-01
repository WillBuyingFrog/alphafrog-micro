from celery import shared_task

from openai import OpenAI

import os
import re

@shared_task(bind=True)
def common_analysis_with_reasoning_model(self, user_prompt, reason_model):

    if reason_model == 'deepseek-reasoner':
        client = OpenAI(api_key=os.getenv('AF_DEEPSEEK_API_KEY'), base_url="https://api.deepseek.com/")

        with open('domestic/statics/common_analysis_prompt.txt', 'r') as f:
            alphafrog_prompt = f.read()

        user_prompt = alphafrog_prompt + user_prompt

        response = client.chat.completions.create(
            model="deepseek-reasoner",
            messages=[{"role": "user", "content": user_prompt}],
            stream=True
        )

        reason_start = False
        output_start = False

        reasoning_content = ""
        output_content = ""

        for chunk in response:
            if chunk.choices[0].delta.reasoning_content:
                if not reason_start:
                    reason_start = True
                reasoning_content += chunk.choices[0].delta.reasoning_content
            elif chunk.choices[0].delta.content:
                if not output_start:
                    output_start = True
                output_content += chunk.choices[0].delta.content
        
        # 将格式化的输出内容写入临时文件并执行

        # （假设LLM返回的代码不是恶意的）
        # 将LLM返回的python脚本代码写到指定的临时文件夹中，然后按顺序执行文件
        # 执行结果为文件，需要持久化保存到另一指定目录
        # 临时的脚本文件需要定时删除
        


def save_code_blocks(input_content, output_suffix, output_dir) -> int:
    """
    从指定文件中读取输入内容，解析每个需求的代码块，并保存为单独的Python脚本文件。

    :param input_content: 输入内容
    :param output_suffix: 自定义后缀
    :param output_dir: 输出文件的目录

    :return: 0 成功，-1 未找到任何需求或代码块，-2 发生其他错误
    """
    # 正则表达式匹配需求标题和代码块
    pattern = r"(需求\d+)\s*```python\n(.*?)\n```"
    
    try:
        # 确保输出目录存在，如果不存在则创建
        os.makedirs(output_dir, exist_ok=True)

        # 使用正则表达式提取所有需求和代码块
        matches = re.findall(pattern, input_content, re.DOTALL)

        if not matches:
            print("未找到任何需求或代码块，请检查输入文件格式是否正确。")
            return -1

        # 遍历每个需求和代码块
        for i, (requirement, code) in enumerate(matches, start=1):
            # 构造输出文件名
            output_filename = f"question{i}-{output_suffix}.py"
            
            # 拼接完整的输出路径
            full_output_path = os.path.join(output_dir, output_filename)
            
            # 将代码保存到文件
            with open(full_output_path, 'w', encoding='utf-8') as output_file:
                output_file.write(code.strip())  # 去除多余的空白字符
            
            print(f"已保存: {full_output_path}")
            return 0

    except Exception as e:
        print(f"发生错误：{e}")
        return -2