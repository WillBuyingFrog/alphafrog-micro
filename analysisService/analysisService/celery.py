import os

from celery import Celery

os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'analysisService.settings')

app = Celery('analysisService')
app.config_from_object('django.conf:settings', namespace='CELERY')

task_modules = [
    'domestic.tasks.common_analysis_tasks',
]

app.autodiscover_tasks(task_modules)
