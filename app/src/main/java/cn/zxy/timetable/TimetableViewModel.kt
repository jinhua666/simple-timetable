package cn.zxy.timetable

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimetableViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScheduleRepository(application)
    val schedule = repository.schedule.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    init { viewModelScope.launch { runCatching { repository.initialize() }.onFailure { _error.value = "读取课表失败：${it.message}" } } }
    fun clearError() { _error.value = null }
    fun save(value: Schedule, onSaved: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { repository.save(value) }.onSuccess { onSaved() }.onFailure { _error.value = "保存失败：${it.message}" }
            _busy.value = false
        }
    }
    suspend fun parseExcel(bytes: ByteArray, base: Schedule): Schedule = withContext(Dispatchers.Default) { SchoolXlsx.parse(bytes, base) }
}
