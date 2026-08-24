package com.kzhovn.todoapp.context

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TodoDatabase
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowWifiInfo

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WifiContextMonitorTest {
    private lateinit var db: TodoDatabase
    private lateinit var monitor: WifiContextMonitor
    private lateinit var wifiManager: WifiManager
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .setTransactionExecutor(Executor { it.run() })
            .build()
        wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        monitor = WifiContextMonitor(connectivityManager, wifiManager, db.taskContextDao(), CoroutineScope(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `marks place context satisfied when connected to its saved ssid`() = runTest(testDispatcher) {
        val id = db.taskContextDao().insert(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHomeNetwork")
        )

        val wifiInfo = ShadowWifiInfo.newInstance()
        Shadows.shadowOf(wifiInfo).setSSID("MyHomeNetwork")
        Shadows.shadowOf(wifiManager).setConnectionInfo(wifiInfo)

        monitor.refresh()
        advanceUntilIdle()

        assertEquals(true, db.taskContextDao().getById(id)?.isCurrentlySatisfied)
    }

    @Test
    fun `marks place context unsatisfied when connected to a different ssid`() = runTest(testDispatcher) {
        val id = db.taskContextDao().insert(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHomeNetwork", isCurrentlySatisfied = true)
        )

        val wifiInfo = ShadowWifiInfo.newInstance()
        Shadows.shadowOf(wifiInfo).setSSID("CoffeeShopWifi")
        Shadows.shadowOf(wifiManager).setConnectionInfo(wifiInfo)

        monitor.refresh()
        advanceUntilIdle()

        assertEquals(false, db.taskContextDao().getById(id)?.isCurrentlySatisfied)
    }
}
