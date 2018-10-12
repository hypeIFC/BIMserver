package org.bimserver.servlets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import javax.activation.DataHandler;

import org.apache.commons.io.IOUtils;
import org.bimserver.BimServer;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.bimbots.BimBotContext;
import org.bimserver.bimbots.BimBotsException;
import org.bimserver.bimbots.BimBotsOutput;
import org.bimserver.bimbots.BimBotsServiceInterface;
import org.bimserver.bimbots.BimServerBimBotsInput;
import org.bimserver.database.DatabaseSession;
import org.bimserver.database.OldQuery;
import org.bimserver.database.OldQuery.Deep;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.emf.PackageMetaData;
import org.bimserver.emf.Schema;
import org.bimserver.endpoints.VirtualEndPoint;
import org.bimserver.ifc.BasicIfcModel;
import org.bimserver.interfaces.objects.SDeserializerPluginConfiguration;
import org.bimserver.interfaces.objects.SExtendedData;
import org.bimserver.interfaces.objects.SExtendedDataSchema;
import org.bimserver.interfaces.objects.SFile;
import org.bimserver.interfaces.objects.SLongActionState;
import org.bimserver.interfaces.objects.SProject;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.models.store.InternalServicePluginConfiguration;
import org.bimserver.models.store.Revision;
import org.bimserver.notifications.ProgressTopic;
import org.bimserver.notifications.TopicRegisterException;
import org.bimserver.plugins.PluginConfiguration;
import org.bimserver.plugins.SchemaName;
import org.bimserver.plugins.deserializers.DeserializeException;
import org.bimserver.plugins.deserializers.Deserializer;
import org.bimserver.plugins.deserializers.DeserializerPlugin;
import org.bimserver.shared.StreamingSocketInterface;
import org.bimserver.shared.exceptions.PluginException;
import org.bimserver.shared.exceptions.ServerException;
import org.bimserver.shared.exceptions.UserException;
import org.bimserver.shared.interfaces.NotificationInterface;
import org.bimserver.shared.interfaces.NotificationInterfaceAdaptor;
import org.bimserver.shared.interfaces.NotificationRegistryInterface;
import org.bimserver.shared.interfaces.ServiceInterface;
import org.bimserver.utils.InputStreamDataSource;
import org.bimserver.webservices.ServiceMap;
import org.bimserver.webservices.authorization.Authorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BimBotRunner implements Runnable {
	private static final Logger LOGGER = LoggerFactory.getLogger(BimBotRunner.class);
	private BimServer bimServer;
	private InputStream inputStream;
	private String inputType;
	private Authorization authorization;
	private InternalServicePluginConfiguration foundService;
	private BimBotsServiceInterface bimBotsServiceInterface;
	private String contextId;
	private StreamingSocketInterface streamingSocketInterface;
	private Long topicId;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private Long endPointId;

	public BimBotRunner(BimServer bimServer, InputStream inputStream, String contextId, String inputType, Authorization authorization,
			InternalServicePluginConfiguration foundService, BimBotsServiceInterface bimBotsServiceInterface) {
		this(bimServer, inputStream, contextId, inputType, authorization, foundService, bimBotsServiceInterface, null, null, null);
	}
	
	public BimBotRunner(BimServer bimServer, InputStream inputStream, String contextId, String inputType, Authorization authorization,
			InternalServicePluginConfiguration foundService, BimBotsServiceInterface bimBotsServiceInterface, StreamingSocketInterface streamingSocketInterface, Long topicId, Long endPointId) {
		this.bimServer = bimServer;
		this.inputStream = inputStream;
		this.contextId = contextId;
		this.inputType = inputType;
		this.authorization = authorization;
		this.foundService = foundService;
		this.bimBotsServiceInterface = bimBotsServiceInterface;
		this.streamingSocketInterface = streamingSocketInterface;
		this.topicId = topicId;
		this.endPointId = endPointId;
	}
	
	public BimBotsOutput runBimBot() throws UserException, IOException {
		BimBotContext bimBotContext = new BimBotContext() {
			@Override
			public void updateProgress(String label, int percentage) {
				if (streamingSocketInterface != null) {
					ObjectNode message = objectMapper.createObjectNode();
					message.put("type", "progress");
					message.put("topicId", topicId);
					ObjectNode payload = objectMapper.createObjectNode();
					payload.put("progress", percentage);
					payload.put("label", label);
					message.set("payload", payload);
					streamingSocketInterface.send(message);
				}
			}
		};

		try (DatabaseSession session = bimServer.getDatabase().createSession()) {
			if (bimServer.getServerSettingsCache().getServerSettings().isStoreServiceRuns()) {
				LOGGER.info("Storing intermediate results");
				// When we store service runs, we can just use the streaming deserializer to stream directly to the database, after that we'll trigger the actual service
				
				// Create or find project and link user and service to project
				// Checkin stream into project
				// Trigger service
				
				ServiceMap serviceMap = bimServer.getServiceFactory().get(authorization, AccessMethod.INTERNAL);
				ServiceInterface serviceInterface = serviceMap.get(ServiceInterface.class);
				
				SProject project = null;
				String uuid = contextId;
				if (uuid != null) {
					project = serviceInterface.getProjectByUuid(uuid);
				} else {
					// TODO use inputType to determine schema to make this work for IFC4
					project = serviceInterface.addProject("tmp-" + new Random().nextInt(), "ifc2x3tc1");							
				}

				SDeserializerPluginConfiguration deserializer = serviceInterface.getSuggestedDeserializerForExtension("ifc", project.getOid());
				if (deserializer == null) {
					throw new BimBotsException("No deserializer found");
				}
				Long topicId = serviceInterface.initiateCheckin(project.getOid(), deserializer.getOid());
				ProgressTopic progressTopic = null;
				VirtualEndPoint virtualEndpoint = null;
				try {
					progressTopic = bimServer.getNotificationsManager().getProgressTopic(topicId);
					virtualEndpoint = new VirtualEndPoint(){
						@Override
						public NotificationInterface getNotificationInterface() {
							return new NotificationInterfaceAdaptor(){
								@Override
								public void progress(Long topicId, SLongActionState state) throws UserException, ServerException {
									bimBotContext.updateProgress(state.getTitle(), state.getProgress());
								}
							};
						}
					};
					progressTopic.register(virtualEndpoint);
				} catch (TopicRegisterException e1) {
					e1.printStackTrace();
				}
				serviceMap.get(NotificationRegistryInterface.class).registerProgressHandler(topicId, endPointId);
				serviceInterface.checkinInitiated(topicId, project.getOid(), "Auto checkin", deserializer.getOid(), -1L, "s", new DataHandler(new InputStreamDataSource(inputStream)), false, true);
				project = serviceInterface.getProjectByPoid(project.getOid());
				
				PackageMetaData packageMetaData = bimServer.getMetaDataManager().getPackageMetaData(project.getSchema());
				IfcModelInterface model = new BasicIfcModel(packageMetaData, null);
				try {
					Revision revision = session.get(project.getLastRevisionId(), OldQuery.getDefault());
					session.getMap(model, new OldQuery(packageMetaData, project.getId(), revision.getId(), revision.getOid(), null, Deep.NO));
				} catch (BimserverDatabaseException e) {
					e.printStackTrace();
				}
				
				SchemaName schema = SchemaName.valueOf(inputType);
				
				BimServerBimBotsInput input = new BimServerBimBotsInput(bimServer, authorization.getUoid(), schema, null, model, false);
				BimBotsOutput output = bimBotsServiceInterface.runBimBot(input, bimBotContext, bimServer.getSConverter().convertToSObject(foundService.getSettings()));
				
				SExtendedData extendedData = new SExtendedData();
				SFile file = new SFile();
				file.setData(output.getData());
				file.setFilename(output.getContentDisposition());
				file.setMime(output.getContentType());
				file.setSize(output.getData().length);
				Long fileId = serviceInterface.uploadFile(file);
				extendedData.setFileId(fileId);
				extendedData.setTitle(output.getTitle());
				SExtendedDataSchema extendedDataSchema = null;
				try {
					extendedDataSchema = serviceInterface.getExtendedDataSchemaByName(output.getSchemaName());
				} catch (UserException e) {
					extendedDataSchema = new SExtendedDataSchema();
					extendedDataSchema.setContentType(output.getContentType());
					extendedDataSchema.setName(output.getSchemaName());
					serviceInterface.addExtendedDataSchema(extendedDataSchema);
				}
				extendedData.setSchemaId(extendedDataSchema.getOid());
				serviceInterface.addExtendedDataToRevision(project.getLastRevisionId(), extendedData);
				
				output.setContextId(project.getUuid());
				
				if (progressTopic != null) {
					try {
						progressTopic.unregister(virtualEndpoint);
					} catch (TopicRegisterException e) {
						e.printStackTrace();
					}
				}
				
				return output;
			} else {
				// When we don't store the service runs, there is no other way than to just use the old deserializer and run the service from the EMF model
				LOGGER.info("NOT Storing intermediate results");

				DeserializerPlugin deserializerPlugin = bimServer.getPluginManager().getFirstDeserializer("ifc", Schema.IFC2X3TC1, true);
				if (deserializerPlugin == null) {
					throw new BimBotsException("No deserializer plugin found");
				}
				
				byte[] data = IOUtils.toByteArray(inputStream);
				SchemaName schema = SchemaName.valueOf(inputType);
				
				Deserializer deserializer = deserializerPlugin.createDeserializer(new PluginConfiguration());
				PackageMetaData packageMetaData = bimServer.getMetaDataManager().getPackageMetaData("ifc2x3tc1");
				deserializer.init(packageMetaData);
				IfcModelInterface model = deserializer.read(new ByteArrayInputStream(data), schema.name(), data.length, null);
				
				BimServerBimBotsInput input = new BimServerBimBotsInput(bimServer, authorization.getUoid(), schema, data, model, true);
				BimBotsOutput output = bimBotsServiceInterface.runBimBot(input, bimBotContext, bimServer.getSConverter().convertToSObject(foundService.getSettings()));
				
				return output;
			}
		} catch (BimBotsException e) {
			LOGGER.error("", e);
		} catch (DeserializeException e) {
			LOGGER.error("", e);
		} catch (PluginException e) {
			LOGGER.error("", e);
		} catch (ServerException e) {
			LOGGER.error("", e);
		}
		return null;
	}

	@Override
	public void run() {
		try {
			BimBotsOutput bimBotsOutput = runBimBot();
			ObjectNode message = objectMapper.createObjectNode();
			message.put("topicId", topicId);
			message.put("type", "output");
			message.set("payload", bimBotsOutput.toJson());
			streamingSocketInterface.send(message);
			byte[] data = bimBotsOutput.getData();
			streamingSocketInterface.send(data, 0, data.length);
		} catch (Exception e) {
			LOGGER.error("", e);
		}
	}
}