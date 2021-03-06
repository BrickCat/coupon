package top.devgo.coupon.core;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.log4j.Logger;

import top.devgo.coupon.core.task.Task;

/**
 * crawler的主控类
 * @author DD
 *
 */
public class CrawlerManager {
	private static Logger logger = Logger.getLogger(CrawlerManager.class.getName());
	
	/**
	 * 线程安全的优先级队列，用来存放task
	 */
	private PriorityBlockingQueue<Task> taskQueue;
	/**
	 * 线程安全的HttpClientConnection连接池
	 */
	private PoolingHttpClientConnectionManager connectionManager;
	private CloseableHttpClient httpclient; 
	/**
	 * Crawler线程池
	 */
	private ExecutorService crawlerThreadPool;

    private Thread taskManager;//用来管理task的执行
    private final CrawlerManager self = this;

	/**
	 * 初始化stage
	 * @param config
	 */
	private void initStage(Config config) {
		taskQueue = new PriorityBlockingQueue<Task>(config.getTaskQueueCapacity());

		// Create an HttpClient with the ThreadSafeClientConnManager.
        // This connection manager must be used if more than one thread will
        // be using the HttpClient.
		connectionManager = new PoolingHttpClientConnectionManager();
		connectionManager.setValidateAfterInactivity(config.getConnectionValidateInterval());
        connectionManager.setDefaultMaxPerRoute(config.getMaxConnections()/2);//设置每个路由(route)的最大连接数。
        //这里route的概念可以理解为 运行环境机器 到 目标机器的一条线路。
        // 举例来说，我们使用HttpClient的实现来分别请求 www.baidu.com 的资源和 www.bing.com 的资源那么他就会产生两个route。
        // 这里为什么要特别提到route最大连接数这个参数呢，因为这个参数的默认值为2，如果不设置这个参数值默认情况下对于同一个目标机器的
        // 最大并发连接只有2个！这意味着如果你正在执行一个针对某一台目标机器的抓取任务的时候，哪怕你设置连接池的最大连接数为200，
        // 但是实际上还是只有2个连接在工作，其他剩余的198个连接都在等待，都是为别的目标机器服务的。
		connectionManager.setMaxTotal(config.getMaxConnections());

		httpclient = HttpClients.custom()
				.setConnectionManager(connectionManager)
				.setDefaultRequestConfig(
						RequestConfig.custom().setConnectTimeout(config.getConnectionTimeout())
								.setSocketTimeout(config.getConnectionTimeout()).build())
				.build();

		crawlerThreadPool = Executors.newFixedThreadPool(config.getMaxCrawlers());
		
        addTasksToQueue(config.getBeginningTasks());

        taskManager = new Thread(new Runnable() {
        	private int taskScanInterval = config.getTaskScanInterval();
            @Override
            public void run() {
                while (started) {
                    int tasks = taskQueue.size();
                    int workingThread = ((ThreadPoolExecutor)crawlerThreadPool).getActiveCount();
                    if (tasks < 1) {
                        logger.info("暂无新任务，尚有"+workingThread+"个任务在执行。");
                        if (workingThread < 1) {
                            stop();
                        }
                    }else{
                        int jobs = Math.min(tasks, (config.getMaxCrawlers()-workingThread+1) * Math.max(2, (tasks/config.getTaskQueueCapacity())*(config.getTaskScanInterval()/1000)));//提供当前空余worker数2倍或更多倍的任务
                        for (int i = 0; i < jobs; i++) {
                            Task task = taskQueue.poll();
                            crawlerThreadPool.submit(new Crawler(httpclient, task, self));
                        }
                        logger.info("任务总数"+tasks+"，新增"+jobs+"个任务，尚有"+workingThread+"个任务在执行。");
                    }
                    taskScanInterval = calcNewInterval(taskScanInterval, tasks, workingThread, config.getMaxCrawlers());
                    sleep(taskScanInterval);
                }
            }

            private int calcNewInterval(int oldInterval, int tasks, int working, int poolSize) {
                int times = tasks / poolSize;
                if ((times >= 100 || tasks < 1) && oldInterval < 1000)
                    return oldInterval * 2;
                else if ((tasks > 0 && working < poolSize) && oldInterval > 20)
                    return oldInterval / 2;
                else
                    return oldInterval;
            }

        });
        taskManager.start();
	}

    private boolean started = false;//记录本容器是否启动

	public void start(final Config config) {
		if(started){//已被启动
            addTasksToQueue(config.getBeginningTasks());
            logger.info("crawler already started, add seeds: "+ config.getBeginningTasks().size());
			return;
		}
		
		started = true;
		initStage(config);

		logger.info("crawler start, at: "+ new Date());
	}

	public void stop() {
		if(!started){//未启动
			return;
		}
		
		for (Task task : taskQueue) {
			logger.info("未执行task："+task.toString());
		}
		
		started = false;
		waitUntilFinish(10L);
		
		logger.info("crawler stop, at: "+ new Date());
	}
	
	private void waitUntilFinish(long timeout) {
//		int workingThread = ((ThreadPoolExecutor)crawlerThreadPool).getActiveCount();
//		while (workingThread > 0) {
//			sleep(3);
//		}
		try {
			if (!crawlerThreadPool.awaitTermination(timeout, TimeUnit.SECONDS)) {
				
				BlockingQueue<Runnable> tasks = ((ThreadPoolExecutor)crawlerThreadPool).getQueue();
				for (Runnable task : tasks) {
					logger.info("停止或取消task："+task.toString());
				}
				
				crawlerThreadPool.shutdownNow();
			}
			httpclient.close();
			connectionManager.close();
//			MongoDBUtil.close();
		} catch (IOException e) {
			logger.error("", e);
		} catch (InterruptedException e) {
			logger.warn("Interrupted!", e);
			Thread.currentThread().interrupt();
		}
	}
	
	protected void sleep(long mills) {
		try {
			Thread.sleep(mills);
		} catch (InterruptedException ignored) {
			logger.warn("Interrupted!", ignored);
			Thread.currentThread().interrupt();
		}
	}
	
	public void addTaskToQueue(Task task) {
		taskQueue.put(task);
	}

    private void addTasksToQueue(List<Task> seeds) {
        if(seeds!=null){
            for (Iterator<Task> it = seeds.iterator(); it.hasNext();) {
                Task task = it.next();
                addTaskToQueue(task);
            }
        }
    }

	public int getTaskQueueCount() {
		return taskQueue.size();
	}

	public int getWorkingThreadCount() {
		return ((ThreadPoolExecutor)crawlerThreadPool).getActiveCount();
	}

}
