package net.jadler.httpmocker;

import net.jadler.stubbing.RequestStubbing;
import net.jadler.stubbing.StubbingFactory;
import net.jadler.stubbing.Stubbing;
import net.jadler.rule.HttpMockResponse;
import net.jadler.rule.HttpMockRule;
import net.jadler.exception.JadlerException;
import net.jadler.server.MockHttpServer;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.collections.MultiMap;
import org.apache.commons.collections.map.MultiValueMap;
import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Standard {@link HttpMocker} implementation. You shouldn't create instances of this class
 * on your own, see {@link Jadler} instead. It also acts as a great {@link ResponseProvider}.
 * 
 * This class is stateful and thread-safe.
 */
public class HttpMockerImpl implements HttpMocker, ResponseProvider {

    private final MockHttpServer server;
    private final StubbingFactory stubbingFactory;
    private final List<Stubbing> stubbings;
    private final MultiMap defaultHeaders;
    private List<HttpMockRule> httpMockRules;
    private int defaultStatus;
    private boolean started = false;
    private boolean configurable = true;
    
    private static final Logger logger = LoggerFactory.getLogger(HttpMockerImpl.class);

    
    /**
     * Creates new HttpMocker instance using the given http mock server.
     * Instances of this class should never be created directly, see {@link Jadler} for explanation and tutorial.
     * 
     * @param server mock http server instance this mocker should use
     */
    public HttpMockerImpl(final MockHttpServer server) {
        this(server, new StubbingFactory());
    }
    
    
    /**
     * Package private constructor, for testing purposes only! Allows to define a StubbingFactory instance
     * as well.
     * @param server mock http server instance this mocker should use
     * @param stubbingFactory a factory to create stubbing instances
     */
    HttpMockerImpl(final MockHttpServer server, final StubbingFactory stubbingFactory) {
        Validate.notNull(server, "server cannot be null");
        this.server = server;
        
        this.stubbings = new ArrayList<>();
        this.defaultHeaders = new MultiValueMap();
        this.defaultStatus = HttpServletResponse.SC_OK;
        
        Validate.notNull(stubbingFactory, "stubbingFactory cannot be null");
        this.stubbingFactory = stubbingFactory;
        
        this.httpMockRules = new ArrayList<>();
    }
    
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        if (this.started){
            throw new IllegalStateException("The mock server has been started already.");
        }
        
        logger.debug("starting the underlying mock server...");

        try {
            server.start();
        } catch (final Exception ex) {
            throw new JadlerException("Mock http server start failure", ex);
        }
        this.started = true;
    }
    

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        if (!this.started) {
            throw new IllegalStateException("The mock server hasn't been started yet.");
        }
        
        logger.debug("stopping the underlying mock server...");
        
        try {
            server.stop();
        } catch (final Exception ex) {
            throw new JadlerException("Mock http server shutdown failure", ex);
        }
        this.started = false;
    }
    
    
    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isStarted() {
        return this.started;
    }


    /**
     * Defines default headers to be added to every mock http response
     * @param defaultHeaders default headers to be added to every mock http response 
     */
    @SuppressWarnings("unchecked")
    public void addDefaultHeaders(final MultiMap defaultHeaders) {
        Validate.notNull(defaultHeaders, "defaultHeaders cannot be null, use an empty map instead");
        this.checkConfigurable();
        this.defaultHeaders.putAll(defaultHeaders);
    }
    

    /**
     * Defines default status to be returned in every mock http response (if not redefined in the
     * particular rule)
     * @param defaultStatus status to be returned in every mock http response. Must be at least 1.
     */
    public void setDefaultStatus(final int defaultStatus) {
        Validate.isTrue(defaultStatus >= 0, "defaultStatus mustn't be negative");
        this.checkConfigurable();
        this.defaultStatus = defaultStatus;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public RequestStubbing onRequest() {
        logger.debug("adding new stubbing...");
        this.checkConfigurable();
        
        final Stubbing stubbing = this.stubbingFactory.createStubbing(defaultHeaders, defaultStatus);
        stubbings.add(stubbing);
        return stubbing;
    }
    
    
    /**
     * {@inheritDoc} 
     */
    @Override
    public HttpMockResponse provideResponseFor(final HttpServletRequest req) {
        
        synchronized(this) {
            if (this.configurable) {
                this.configurable = false;
                this.httpMockRules = this.createRules();
            }
        }
        
        for (final HttpMockRule rule : this.httpMockRules) {
            if (rule.matches(req)) {
                final StringBuilder sb = new StringBuilder();
                sb.append("Following rule will be applied:\n");
                sb.append(rule);
                logger.debug(sb.toString());
                
                return rule.nextResponse();
            }
        }
        
        final StringBuilder sb = new StringBuilder();
        sb.append("No suitable rule found. Reason:\n");
        for (final HttpMockRule rule: this.httpMockRules) {
            sb.append("The rule '");
            sb.append(rule);
            sb.append("' cannot be applied. Mismatch:\n");
            sb.append(rule.describeMismatch(req));
            sb.append("\n");
        }
        logger.info(sb.toString());
        
        return null;
    }

    
    /**
     * package private getter useful for testing
     * @return list of created http mock rules
     */
    List<HttpMockRule> getHttpMockRules() {
        return httpMockRules;
    }
    
    
    private List<HttpMockRule> createRules() {
        final List<HttpMockRule> rules = new ArrayList<>();
        for (final Stubbing stub : stubbings) {
            rules.add(stub.createRule());
        }
        return rules;
    }
    
    
    private synchronized void checkConfigurable() {
        if (!this.configurable) {
            throw new IllegalStateException("Once first http request has been served, "
                    + "you can't do any stubbing anymore.");
        }
    }
}