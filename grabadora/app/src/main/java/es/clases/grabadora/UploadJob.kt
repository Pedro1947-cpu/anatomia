package es.clases.grabadora

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Reintenta las subidas pendientes cuando hay internet (aunque la app esté cerrada). */
class UploadJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = Uploader.uploadAll(this)
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 42

        fun schedule(c: Context) {
            if (Store.toUpload(c).isEmpty() || Prefs(c).scriptUrl.isBlank()) return
            val net = if (Prefs(c).wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, UploadJob::class.java))
                .setRequiredNetworkType(net)
                .setPersisted(true)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            c.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
