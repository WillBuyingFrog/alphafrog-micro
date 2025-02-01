from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt

from domestic.tasks.common_analysis_tasks import common_analysis_with_reasoning_model

import json


@csrf_exempt
def common_analysis(request):
    if request.method == 'POST':
        data = request.body
        data = json.loads(data)

        user_prompt = data['user_prompt']
        reason_model = data['reason_model']
        provider = data['provider']

        task = common_analysis_with_reasoning_model.delay(user_prompt, reason_model, provider)

        return JsonResponse({'task_id': task.id})
    else:
        return JsonResponse({'error': 'Invalid request method'}, status=405)
