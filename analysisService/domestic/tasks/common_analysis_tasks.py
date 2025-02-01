from celery import shared_task

from openai import OpenAI

import os
import re

@shared_task(bind=True)
def common_analysis_with_reasoning_model(self, user_prompt, reason_model,
                                         reason_provider="deepseek"):

    output_dir_root = os.environ.get('AF_REASONING_OUTPUT_DIR_ROOT')
    output_dir = os.path.join(output_dir_root, self.request.id)
    os.makedirs(output_dir, exist_ok=True)

    api_key = None
    reason_endpoint = None

    # 获取API密钥和服务提供商的endpoint
    try:
        if reason_provider == 'deepseek':
            api_key = os.getenv('AF_DEEPSEEK_API_KEY')
            reason_endpoint = "https://api.deepseek.com/"
        elif reason_provider == 'siliconflow':
            api_key = os.getenv('AF_SILICONFLOW_API_KEY')
            reason_endpoint = "https://api.siliconflow.cn/v1"

        elif reason_provider == 'fireworks':
            api_key = os.getenv('AF_FIREWORKS_API_KEY')
            reason_endpoint = "https://api.fireworks.ai/inference/v1"
    except Exception as e:
        exc_info = {
            'exc_type': type(e).__name__,
            'exc_message': str(e),
            'custom_meta': {'progress': 'error', 'detail': f"获取API密钥失败: {e}"}
        }
        self.update_state(state='FAILURE', meta=exc_info)
        return
        


    if reason_model == 'deepseek-reasoner':
        client = OpenAI(api_key=api_key, base_url=reason_endpoint)

        with open('domestic/statics/common_analysis_prompt.txt', 'r') as f:
            alphafrog_prompt = f.read()

        user_prompt = alphafrog_prompt + user_prompt

        # 处理不同服务提供商的模型命名区别
        if reason_provider == 'deepseek':
            model_name = "deepseek-reasoner"
        elif reason_provider == 'siliconflow':
            model_name = "deepseek-ai/DeepSeek-R1"
        elif reason_provider == 'fireworks':
            model_name = "accounts/fireworks/models/deepseek-r1"


        response = client.chat.completions.create(
            model=model_name,
            messages=[{"role": "user", "content": user_prompt}],
            stream=True,
            max_tokens=800000
        )

        reason_start = False
        output_start = False

        reasoning_content = ""
        output_content = ""

        if reason_provider == 'deepseek':
            for chunk in response:
                if chunk.choices[0].delta.reasoning_content:
                    if not reason_start:
                        reason_start = True
                        self.update_state(state='PROGRESS', meta={'progress': 'reasoning'})
                    reasoning_content += chunk.choices[0].delta.reasoning_content
                elif chunk.choices[0].delta.content:
                    if not output_start:
                        output_start = True
                        self.update_state(state='PROGRESS', meta={'progress': 'output'})
                    output_content += chunk.choices[0].delta.content
        else:
            self.update_state(state='PROGRESS', meta={'progress': 'reasoning'})
            try:
                for chunk in response:
                    if chunk.choices[0].delta.content:
                        output_content += chunk.choices[0].delta.content
                        # 是否输出完推理内容
                        if not output_start and '</think>' in chunk.choices[0].delta.content:
                            output_start = True
                            self.update_state(state='PROGRESS', meta={'progress': 'output'})
                    
                    if len(output_content) % 1000 <= 10:
                        if not output_start:
                            self.update_state(state='PROGRESS', meta={'progress': 'reasoning', 'cumulative_length': len(output_content)})
                        else:
                            self.update_state(state='PROGRESS', meta={'progress': 'output', 'cumulative_length': len(output_content)})
            except Exception as e:
                exc_info = {
                    'exc_type': type(e).__name__,
                    'exc_message': str(e),
                    'custom_meta': {'progress': 'error', 'detail': f"推理失败: {e}"}
                }   
                self.update_state(state='FAILURE', meta=exc_info)
                return
            

            # 按照</think>分割output_content
            if '</think>' in output_content:
                reasoning_content, output_content = output_content.split('</think>', 1)
                reasoning_content = reasoning_content.strip()
                output_content = output_content.strip()

            with open(os.path.join(output_dir, 'reasoning_content.txt'), 'w') as f:
                f.write(reasoning_content)
            with open(os.path.join(output_dir, 'output_content.txt'), 'w') as f:
                f.write(output_content)
            
        
        self.update_state(state='PROGRESS', meta={'progress': 'done'})
        # 将格式化的输出内容写入临时文件并执行

        # （假设LLM返回的代码不是恶意的）
        # 将LLM返回的python脚本代码写到指定的临时文件夹中，然后按顺序执行文件
        # 执行结果为文件，需要持久化保存到另一指定目录
        # 临时的脚本文件需要定时删除

        save_code_status = save_code_blocks(output_content, "deepseek-reasoner", output_dir)

        print(f"save_code_status: {save_code_status}")

        if save_code_status == 0:
            self.update_state(state='PROGRESS', meta={'progress': 'done'})
        else:
            exc_info = {
                'exc_type': type(e).__name__,
                'exc_message': str(e),
                'custom_meta': {'progress': 'error', 'detail': f"保存代码失败: {save_code_status}"}
            }
            self.update_state(state='FAILURE', meta=exc_info)
            return
        
        # 执行临时的脚本文件
        
        for file in os.listdir(output_dir):
            if file.endswith('.py'):
                try:
                    exit_code = os.system(f"python {os.path.join(output_dir, file)} --output_path {output_dir}")
                    if exit_code != 0:
                        exc_info = {
                            'exc_type': type(e).__name__,
                            'exc_message': str(e),
                            'custom_meta': {'progress': 'error', 'detail': f"执行脚本 {file} 失败，退出码为 {exit_code}"}
                        }
                        self.update_state(state='FAILURE', meta=exc_info)
                        return
                except Exception as e:
                    exc_info = {
                        'exc_type': type(e).__name__,
                        'exc_message': str(e),
                        'custom_meta': {'progress': 'error', 'detail': f"执行脚本 {file} 失败，退出码为 {exit_code}"}
                    }
                    self.update_state(state='FAILURE', meta=exc_info)
                    return
        
        self.update_state(state='SUCCESS', meta={'progress': 'done'})
                


        


def save_code_blocks(input_content, output_suffix, output_dir) -> int:
    """
    从指定文件中读取输入内容，解析每个需求的代码块，并保存为单独的Python脚本文件。

    :param input_content: 输入内容
    :param output_suffix: 自定义后缀
    :param output_dir: 输出文件的目录

    :return: 0 成功，-1 未找到任何需求或代码块，-2 发生其他错误
    """
    # 正则表达式匹配需求标题和代码块
    pattern = r"需求(\d+)\s*```python\s*(.*?)\s*```"
    
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
            
            # print(f"已保存: {full_output_path}")
        
        return 0

    except Exception as e:
        print(f"发生错误：{e}")
        return -2