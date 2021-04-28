package com.yoyling.aspect;

import com.yoyling.model.vo.BlogDetail;
import com.yoyling.model.vo.Result;
import com.yoyling.util.AopUtils;
import com.yoyling.util.IpAddressUtils;
import com.yoyling.util.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.yoyling.annotation.VisitLogger;
import com.yoyling.config.RedisKeyConfig;
import com.yoyling.entity.VisitLog;
import com.yoyling.entity.Visitor;
import com.yoyling.service.RedisService;
import com.yoyling.service.VisitLogService;
import com.yoyling.service.VisitorService;
import com.yoyling.util.JacksonUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @Description: AOP记录访问日志
 * @Date: 2020-12-04
 */
@Component
@Aspect
public class VisitLogAspect {
	@Autowired
	VisitLogService visitLogService;
	@Autowired
	VisitorService visitorService;
	@Autowired
	RedisService redisService;

	ThreadLocal<Long> currentTime = new ThreadLocal<>();

	/**
	 * 配置切入点
	 */
	@Pointcut("@annotation(visitLogger)")
	public void logPointcut(VisitLogger visitLogger) {
	}

	/**
	 * 配置环绕通知
	 *
	 * @param joinPoint
	 * @return
	 * @throws Throwable
	 */
	@Around("logPointcut(visitLogger)")
	public Object logAround(ProceedingJoinPoint joinPoint, VisitLogger visitLogger) throws Throwable {
		currentTime.set(System.currentTimeMillis());
		Object result = joinPoint.proceed();
		int times = (int) (System.currentTimeMillis() - currentTime.get());
		currentTime.remove();
		//获取请求对象
		HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
		//校验访客标识码
		String identification = checkIdentification(request);
		//记录访问日志
		VisitLog visitLog = handleLog(joinPoint, visitLogger, request, result, times, identification);
		visitLogService.saveVisitLog(visitLog);
		return result;
	}

	/**
	 * 校验访客标识码
	 *
	 * @param request
	 * @return
	 */
	private String checkIdentification(HttpServletRequest request) {
		String identification = request.getHeader("identification");
		if (identification == null) {
			//第一次访问，签发uuid并保存到数据库和Redis
			identification = saveUUID(request);
		} else {
			//校验Redis中是否存在uuid
			boolean redisHas = redisService.hasValueInSet(RedisKeyConfig.IDENTIFICATION_SET, identification);
			//Redis中不存在uuid
			if (!redisHas) {
				//校验数据库中是否存在uuid
				boolean mysqlHas = visitorService.hasUUID(identification);
				if (mysqlHas) {
					//数据库存在，保存至Redis
					redisService.saveValueToSet(RedisKeyConfig.IDENTIFICATION_SET, identification);
				} else {
					//数据库不存在，签发新的uuid
					identification = saveUUID(request);
				}
			}
		}
		return identification;
	}

	/**
	 * 签发UUID，并保存至数据库和Redis
	 *
	 * @param request
	 * @return
	 */
	private String saveUUID(HttpServletRequest request) {
		//获取响应对象
		HttpServletResponse response = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getResponse();
		//生成UUID
		String uuid = UUID.randomUUID().toString();
		//添加访客标识码UUID至响应头
		response.addHeader("identification", uuid);
		//暴露自定义header供页面资源使用
		response.addHeader("Access-Control-Expose-Headers", "identification");
		//保存至Redis
		redisService.saveValueToSet(RedisKeyConfig.IDENTIFICATION_SET, uuid);
		//获取访问者基本信息
		String ip = IpAddressUtils.getIpAddress(request);
		String userAgent = request.getHeader("User-Agent");
		Visitor visitor = new Visitor(uuid, ip, userAgent);
		//保存至数据库
		visitorService.saveVisitor(visitor);
		return uuid;
	}

	/**
	 * 设置VisitLogger对象属性
	 *
	 * @param joinPoint
	 * @param visitLogger
	 * @param result
	 * @param times
	 * @return
	 */
	private VisitLog handleLog(ProceedingJoinPoint joinPoint, VisitLogger visitLogger, HttpServletRequest request, Object result,
	                           int times, String identification) {
		String uri = request.getRequestURI();
		String method = request.getMethod();
		String behavior = visitLogger.behavior();
		String content = visitLogger.content();
		String ip = IpAddressUtils.getIpAddress(request);
		String userAgent = request.getHeader("User-Agent");
		Map<String, Object> requestParams = AopUtils.getRequestParams(joinPoint);
		Map<String, String> map = judgeBehavior(behavior, content, requestParams, result);
		VisitLog log = new VisitLog(identification, uri, method, behavior, map.get("content"), map.get("remark"), ip, times, userAgent);
		log.setParam(StringUtils.substring(JacksonUtils.writeValueAsString(requestParams), 0, 2000));
		return log;
	}

	/**
	 * 根据访问行为，设置对应的访问内容或备注
	 *
	 * @param behavior
	 * @param content
	 * @param requestParams
	 * @param result
	 * @return
	 */
	private Map<String, String> judgeBehavior(String behavior, String content, Map<String, Object> requestParams, Object result) {
		Map<String, String> map = new HashMap<>();
		String remark = "";
		if (behavior.equals("访问页面") && (content.equals("首页") || content.equals("动态"))) {
			int pageNum = (int) requestParams.get("pageNum");
			remark = "第" + pageNum + "页";
		} else if (behavior.equals("查看博客")) {
			Result res = (Result) result;
			if (res.getCode() == 200) {
				BlogDetail blog = (BlogDetail) res.getData();
				String title = blog.getTitle();
				content = title;
				remark = "文章标题：" + title;
			}
		} else if (behavior.equals("搜索博客")) {
			Result res = (Result) result;
			if (res.getCode() == 200) {
				String query = (String) requestParams.get("query");
				content = query;
				remark = "搜索内容：" + query;
			}
		} else if (behavior.equals("查看分类")) {
			String categoryName = (String) requestParams.get("categoryName");
			int pageNum = (int) requestParams.get("pageNum");
			content = categoryName;
			remark = "分类名称：" + categoryName + "，第" + pageNum + "页";
		} else if (behavior.equals("查看标签")) {
			String tagName = (String) requestParams.get("tagName");
			int pageNum = (int) requestParams.get("pageNum");
			content = tagName;
			remark = "标签名称：" + tagName + "，第" + pageNum + "页";
		} else if (behavior.equals("点击友链")) {
			String nickname = (String) requestParams.get("nickname");
			content = nickname;
			remark = "友链名称：" + nickname;
		}
		map.put("remark", remark);
		map.put("content", content);
		return map;
	}
}