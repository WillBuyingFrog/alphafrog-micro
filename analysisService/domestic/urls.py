from django.urls import path

from domestic.views.tasks import get_task_status
from domestic.views.common_analysis import common_analysis

urlpatterns = [
    path('analysis/common/v1', common_analysis, name='common_analysis'),
    path('task/status/', get_task_status, name='get_task_status'),
]
