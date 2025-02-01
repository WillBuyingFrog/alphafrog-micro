from celery.result import AsyncResult

from django.http import JsonResponse



def get_task_status(request):
    task_id = request.GET.get('task_id')
    task = AsyncResult(task_id)
    return JsonResponse({
            'status': task.status,
            'meta': task.info
        })