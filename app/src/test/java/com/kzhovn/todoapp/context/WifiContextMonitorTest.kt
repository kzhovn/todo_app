package com.kzhovn.todoapp.context

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kzhovn.todoapp.data.ContextType
import com.kzhovn.todoapp.data.TaskContext
import com.kzhovn.todoapp.data.TodoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowWifiInfo

@RunWith(RobolectricTestRunner::class)
class WifiContextMonitorTest {
    private lateinit var db: TodoDatabase
    private lateinit var monitor: WifiContextMonitor
    private lateinit var wifiManager: WifiManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java).allowMainThreadQueries().build()
        wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        monitor = WifiContextMonitor(connectivityManager, wifiManager, db.taskContextDao(), CoroutineScope(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `marks place context satisfied when connected to its saved ssid`() = runBlocking {
        val id = db.taskContextDao().insert(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHomeNetwork")
        )

        val wifiInfo = ShadowWifiInfo.newInstance()
        Shadows.shadowOf(wifiInfo).setSSID("MyHomeNetwork")
        Shadows.shadowOf(wifiManager).setConnectionInfo(wifiInfo)

        monitor.refresh()

        assertEquals(true, db.taskContextDao().getById(id)?.isCurrentlySatisfied)
    }

    @Test
    fun `marks place context unsatisfied when connected to a different ssid`() = runBlocking {
        val id = db.taskContextDao().insert(
            TaskContext(name = "Home", type = ContextType.PLACE, wifiSsid = "MyHomeNetwork", isCurrentlySatisfied = true)
        )

        val wifiInfo = ShadowWifiInfo.newInstance()
        Shadows.shadowOf(wifiInfo).setSSID("CoffeeShopWifi")
        Shadows.shadowOf(wifiManager).setConnectionInfo(wifiInfo)

        monitor.refresh()

        assertEquals(false, db.taskContextDao().getById(id)?.isCurrentlySatisfied)
    }
}
