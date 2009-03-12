package org.apache.activemq.broker.openwire;

import static org.apache.activemq.broker.openwire.OpenWireSupport.createConnectionInfo;
import static org.apache.activemq.broker.openwire.OpenWireSupport.createMessage;
import static org.apache.activemq.broker.openwire.OpenWireSupport.createProducerInfo;
import static org.apache.activemq.broker.openwire.OpenWireSupport.createSessionInfo;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.JMSException;

import org.apache.activemq.Connection;
import org.apache.activemq.broker.Destination;
import org.apache.activemq.broker.MessageDelivery;
import org.apache.activemq.broker.Router;
import org.apache.activemq.broker.openwire.OpenWireMessageDelivery;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ConnectionInfo;
import org.apache.activemq.command.ProducerAck;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.command.SessionInfo;
import org.apache.activemq.dispatch.IDispatcher.DispatchContext;
import org.apache.activemq.dispatch.IDispatcher.Dispatchable;
import org.apache.activemq.flow.Flow;
import org.apache.activemq.flow.IFlowController;
import org.apache.activemq.flow.IFlowDrain;
import org.apache.activemq.flow.ISinkController;
import org.apache.activemq.flow.ISourceController;
import org.apache.activemq.flow.ISinkController.FlowUnblockListener;
import org.apache.activemq.metric.MetricAggregator;
import org.apache.activemq.metric.MetricCounter;
import org.apache.activemq.queue.SingleFlowRelay;
import org.apache.activemq.transport.DispatchableTransport;
import org.apache.activemq.transport.TransportFactory;

public class RemoteProducer extends Connection implements Dispatchable, FlowUnblockListener<MessageDelivery> {

    private final MetricCounter rate = new MetricCounter();

    private AtomicLong messageIdGenerator;
    private int priority;
    private int priorityMod;
    private int counter;
    private int producerId;
    private Destination destination;
    private String property;
    private MetricAggregator totalProducerRate;
    private MessageDelivery next;
    private DispatchContext dispatchContext;
    private String filler;
    private int payloadSize = 20;
    private URI uri;
    private ConnectionInfo connectionInfo;
    private SessionInfo sessionInfo;
    private ProducerInfo producerInfo;
    private ActiveMQDestination activemqDestination;

    private WindowLimiter<MessageDelivery> outboundLimiter;

    private IFlowController<MessageDelivery> outboundController;
    
    public void start() throws Exception {
        
        if( payloadSize>0 ) {
            StringBuilder sb = new StringBuilder(payloadSize);
            for( int i=0; i < payloadSize; ++i) {
                sb.append((char)('a'+(i%26)));
            }
            filler = sb.toString();
        }
        
        rate.name("Producer " + name + " Rate");
        totalProducerRate.add(rate);

        transport = TransportFactory.compositeConnect(uri);
        transport.setTransportListener(this);
        if(transport instanceof DispatchableTransport)
        {
            DispatchableTransport dt = ((DispatchableTransport)transport);
            dt.setName(name + "-client-transport");
            dt.setDispatcher(getDispatcher());
        }
        super.setTransport(transport);
       
        super.initialize();
        transport.start();
        
        if( destination.getDomain().equals( Router.QUEUE_DOMAIN ) ) {
            activemqDestination = new ActiveMQQueue(destination.getName().toString());
        } else {
            activemqDestination = new ActiveMQTopic(destination.getName().toString());
        }
        
        connectionInfo = createConnectionInfo();
        transport.oneway(connectionInfo);
        sessionInfo = createSessionInfo(connectionInfo);
        transport.oneway(sessionInfo);
        producerInfo = createProducerInfo(sessionInfo);
        producerInfo.setWindowSize(1024*4);
        transport.oneway(producerInfo);
        
        dispatchContext = getDispatcher().register(this, name + "-client");
        dispatchContext.requestDispatch();
    }
    
    protected void initialize() {
        Flow ouboundFlow = new Flow(name, false);
        outboundLimiter = new WindowLimiter<MessageDelivery>(true, ouboundFlow, outputWindowSize, outputResumeThreshold);
        outputQueue = new SingleFlowRelay<MessageDelivery>(ouboundFlow, name + "-outbound", outboundLimiter);
        outboundController = outputQueue.getFlowController(ouboundFlow);

        if (transport instanceof DispatchableTransport) {
            outputQueue.setDrain(new IFlowDrain<MessageDelivery>() {

                public void drain(MessageDelivery message, ISourceController<MessageDelivery> controller) {
                    write(message);
                }
            });

        } else {
            blockingTransport = true;
            blockingWriter = Executors.newSingleThreadExecutor();
            outputQueue.setDrain(new IFlowDrain<MessageDelivery>() {
                public void drain(final MessageDelivery message, ISourceController<MessageDelivery> controller) {
                    write(message);
                };
            });
        }
    }
    
    public void stop() throws Exception
    {
    	dispatchContext.close(false);
    	super.stop();
    }

    
    public void onCommand(Object command) {
        try {
            if (command.getClass() == ProducerAck.class) {
                ProducerAck fc = (ProducerAck) command;
                synchronized (outputQueue) {
                    outboundLimiter.onProtocolCredit(fc.getSize());
                }
            } else {
                onException(new Exception("Unrecognized command: " + command));
            }
        } catch (Exception e) {
            onException(e);
        }
    }
    
	public void onFlowUnblocked(ISinkController<MessageDelivery> controller) {
		dispatchContext.requestDispatch();
	}

	public boolean dispatch() {
		while(true)
		{
			
			if(next == null)
			{
	            int priority = this.priority;
	            if (priorityMod > 0) {
	                priority = counter % priorityMod == 0 ? 0 : priority;
	            }
	
	            ActiveMQTextMessage msg = createMessage(producerInfo, activemqDestination, priority, createPayload());
                if (property != null) {
                    try {
                        msg.setStringProperty(property, property);
                    } catch (JMSException e) {
                        new RuntimeException(e);
                    }
                }
	            next = new OpenWireMessageDelivery(msg);
			}
	        
			//If flow controlled stop until flow control is lifted.
			if(outboundController.isSinkBlocked())
			{
				if(outboundController.addUnblockListener(this))
				{
					return true;
				}
			}
			
	        getSink().add(next, null);
	        rate.increment();
	        next = null;
		}
	}

    private String createPayload() {
        if( payloadSize>=0 ) {
            StringBuilder sb = new StringBuilder(payloadSize);
            sb.append(name);
            sb.append(':');
            sb.append(++counter);
            sb.append(':');
            int length = sb.length();
            if( length <= payloadSize ) {
                sb.append(filler.subSequence(0, payloadSize-length));
                return sb.toString();
            } else {
               return sb.substring(0, payloadSize); 
            }
        } else {
            return name+":"+(++counter);
        }
    }
	
	public void setName(String name) {
        this.name = name;
    }

    public AtomicLong getMessageIdGenerator() {
        return messageIdGenerator;
    }

    public void setMessageIdGenerator(AtomicLong msgIdGenerator) {
        this.messageIdGenerator = msgIdGenerator;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int msgPriority) {
        this.priority = msgPriority;
    }

    public int getPriorityMod() {
        return priorityMod;
    }

    public void setPriorityMod(int priorityMod) {
        this.priorityMod = priorityMod;
    }

    public int getProducerId() {
        return producerId;
    }

    public void setProducerId(int producerId) {
        this.producerId = producerId;
    }

    public Destination getDestination() {
        return destination;
    }

    public void setDestination(Destination destination) {
        this.destination = destination;
    }

    public String getProperty() {
        return property;
    }

    public void setProperty(String property) {
        this.property = property;
    }

    public MetricAggregator getTotalProducerRate() {
        return totalProducerRate;
    }

    public void setTotalProducerRate(MetricAggregator totalProducerRate) {
        this.totalProducerRate = totalProducerRate;
    }

    public MetricCounter getRate() {
        return rate;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public void setPayloadSize(int messageSize) {
        this.payloadSize = messageSize;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }
}

