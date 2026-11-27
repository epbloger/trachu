package ru.epbloger.trachu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.epbloger.trachu.core.isFinancialResetConfirmed

/** Confirmation and PIN stay in RAM. Closing or locking discards both. */
@Composable internal fun FinancialResetDialog(protected:Boolean,busy:Boolean,onClose:()->Unit,
                                               onBackup:()->Unit,onDelete:(String,String)->Unit) {
    var step by remember{mutableStateOf(1)}
    var confirmation by remember{mutableStateOf("")}
    var pin by remember{mutableStateOf("")}
    AlertDialog(
        onDismissRequest={if(!busy)onClose()},
        title={Text(if(step==1)"Удалить все данные?" else "Подтвердите удаление")},
        text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(step==1){
                    Text("Все счета, операции, бюджеты, цели, долги, чеки, свои категории, курсы и расписания будут удалены. Стандартные категории восстановятся.")
                    Text("Отменить удаление нельзя. Рекомендуем сначала сохранить зашифрованную резервную копию. Уже сохранённые файлы не удаляются.")
                    OutlinedButton(onClick=onBackup,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("reset-backup")){
                        Text("Создать резервную копию")
                    }
                    Text("После сохранения копии снова откройте удаление данных. PIN и настройки приложения сохранятся.",style=MaterialTheme.typography.bodySmall)
                }else{
                    Text("Введите слово ПОДТВЕРЖДАЮ, чтобы удалить финансовые данные без возможности отмены.")
                    Field("Подтверждение",confirmation,{if(!busy)confirmation=it.take(64)})
                    if(protected)PinField("Текущий PIN",pin){if(!busy)pin=it}
                }
            }
        },
        dismissButton={TextButton(onClick=onClose,enabled=!busy,modifier=Modifier.testTag("reset-cancel")){Text(if(step==1)"Нет" else "Отмена")}},
        confirmButton={
            if(step==1)TextButton(onClick={step=2},enabled=!busy,modifier=Modifier.testTag("reset-continue")){Text("Да, продолжить")}
            else Button(onClick={onDelete(confirmation,pin);pin=""},enabled=!busy&&isFinancialResetConfirmed(confirmation)&&(!protected||pin.length>=4),
                modifier=Modifier.testTag("reset-confirm"),
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)){
                Text(if(busy)"Удаление…" else "Удалить навсегда")
            }
        }
    )
}
