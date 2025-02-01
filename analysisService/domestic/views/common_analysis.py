from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt

import json


@csrf_exempt
def common_analysis(request):
    if request.method == 'POST':
        data = request.body
        data = json.loads(data)

        user_prompt = data['user_prompt']
        reason_model = data['reason_model']
        