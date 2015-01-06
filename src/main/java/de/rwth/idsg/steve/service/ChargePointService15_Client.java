package de.rwth.idsg.steve.service;

import de.rwth.idsg.steve.OcppVersion;
import de.rwth.idsg.steve.handler.ocpp15.*;
import de.rwth.idsg.steve.repository.RequestTaskStore;
import de.rwth.idsg.steve.repository.ReservationRepository;
import de.rwth.idsg.steve.repository.UserRepository;
import de.rwth.idsg.steve.repository.dto.ChargePointSelect;
import de.rwth.idsg.steve.web.RequestTask;
import de.rwth.idsg.steve.web.dto.common.GetDiagnosticsParams;
import de.rwth.idsg.steve.web.dto.common.MultipleChargePointSelect;
import de.rwth.idsg.steve.web.dto.common.RemoteStartTransactionParams;
import de.rwth.idsg.steve.web.dto.common.RemoteStopTransactionParams;
import de.rwth.idsg.steve.web.dto.common.UnlockConnectorParams;
import de.rwth.idsg.steve.web.dto.common.UpdateFirmwareParams;
import de.rwth.idsg.steve.web.dto.ocpp15.CancelReservationParams;
import de.rwth.idsg.steve.web.dto.ocpp15.ChangeAvailabilityParams;
import de.rwth.idsg.steve.web.dto.ocpp15.ChangeConfigurationParams;
import de.rwth.idsg.steve.web.dto.ocpp15.ConfigurationKeyEnum;
import de.rwth.idsg.steve.web.dto.ocpp15.DataTransferParams;
import de.rwth.idsg.steve.web.dto.ocpp15.GetConfigurationParams;
import de.rwth.idsg.steve.web.dto.ocpp15.ReserveNowParams;
import de.rwth.idsg.steve.web.dto.ocpp15.ResetParams;
import de.rwth.idsg.steve.web.dto.ocpp15.SendLocalListParams;
import lombok.extern.slf4j.Slf4j;
import ocpp.cp._2012._06.*;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Client implementation of OCPP V1.5.
 * 
 * This class has methods to create request payloads, and methods to send these to charge points from dynamically created clients.
 * Since there are multiple charge points and their endpoint addresses vary, the clients need to be created dynamically.
 * 
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 * 
 */
@Slf4j
@Service
public class ChargePointService15_Client {

    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private RequestTaskStore requestTaskStore;

    @Autowired
    @Qualifier("ocpp15")
    private JaxWsProxyFactoryBean factory;

    private static final Object LOCK = new Object();

    private ChargePointService create(String endpointAddress) {
        // Should concurrency really be a concern?
        synchronized (LOCK) {
            factory.setAddress(endpointAddress);
            return (ChargePointService) factory.create();
        }
    }

    // -------------------------------------------------------------------------
    // Create Request Payloads
    // -------------------------------------------------------------------------

    private ChangeAvailabilityRequest prepareChangeAvailability(ChangeAvailabilityParams params) {
        return new ChangeAvailabilityRequest()
                .withConnectorId(params.getConnectorId())
                .withType(params.getAvailType());
    }

    private ChangeConfigurationRequest prepareChangeConfiguration(ChangeConfigurationParams params) {
        return new ChangeConfigurationRequest()
                .withKey(params.getConfKey().value())
                .withValue(params.getValue());
    }

    private ClearCacheRequest prepareClearCache() {
        return new ClearCacheRequest();
    }

    private GetDiagnosticsRequest prepareGetDiagnostics(GetDiagnosticsParams params) {
        return new GetDiagnosticsRequest()
                .withLocation(params.getLocation())
                .withRetries(params.getRetries())
                .withRetryInterval(params.getRetryInterval())
                .withStartTime(params.getStart().toDateTime())
                .withStopTime(params.getStop().toDateTime());
    }

    private RemoteStartTransactionRequest prepareRemoteStartTransaction(RemoteStartTransactionParams params) {
        return new RemoteStartTransactionRequest()
                .withIdTag(params.getIdTag())
                .withConnectorId(params.getConnectorId());
    }

    private RemoteStopTransactionRequest prepareRemoteStopTransaction(RemoteStopTransactionParams params) {
        return new RemoteStopTransactionRequest()
                .withTransactionId(params.getTransactionId());
    }

    private ResetRequest prepareReset(ResetParams params) {
        return new ResetRequest()
                .withType(params.getResetType());
    }

    private UnlockConnectorRequest prepareUnlockConnector(UnlockConnectorParams params) {
        return new UnlockConnectorRequest()
                .withConnectorId(params.getConnectorId());
    }

    private UpdateFirmwareRequest prepareUpdateFirmware(UpdateFirmwareParams params) {
        return new UpdateFirmwareRequest()
                .withLocation(params.getLocation())
                .withRetrieveDate(params.getRetrieve().toDateTime())
                .withRetries(params.getRetries())
                .withRetryInterval(params.getRetryInterval());
    }

    /**
     * Dummy implementation. It must be vendor-specific.
     */
    private DataTransferRequest prepareDataTransfer(DataTransferParams params) {
        return new DataTransferRequest()
                .withVendorId(params.getVendorId())
                .withMessageId(params.getMessageId())
                .withData(params.getData());
    }

    private GetConfigurationRequest prepareGetConfiguration(GetConfigurationParams params) {
        List<ConfigurationKeyEnum> enumList = params.getConfKeyList();
        List<String> stringList = new ArrayList<>(enumList.size());
        for (ConfigurationKeyEnum e : enumList) {
            stringList.add(e.value());
        }
        return new GetConfigurationRequest().withKey(stringList);
    }

    private GetLocalListVersionRequest prepareGetLocalListVersion() {
        return new GetLocalListVersionRequest();
    }

    private SendLocalListRequest prepareSendLocalList(SendLocalListParams params) {
        // DIFFERENTIAL update
        if (UpdateType.DIFFERENTIAL.equals(params.getUpdateType())) {
            List<AuthorisationData> auths = new ArrayList<>();

            // Step 1: For the idTags to be deleted, insert only the idTag
            for (String idTag : params.getDeleteList()) {
                auths.add(new AuthorisationData().withIdTag(idTag));
            }

            // Step 2: For the idTags to be added or updated, insert them with their IdTagInfos
            auths.addAll(userService.getAuthData(params.getAddUpdateList()));

            return new SendLocalListRequest()
                    .withListVersion(params.getListVersion())
                    .withUpdateType(UpdateType.DIFFERENTIAL)
                    .withLocalAuthorisationList(auths);

        // FULL update
        } else {
            return new SendLocalListRequest()
                    .withListVersion(params.getListVersion())
                    .withUpdateType(UpdateType.FULL)
                    .withLocalAuthorisationList(userService.getAuthDataOfAllUsers());
        }
    }

    private ReserveNowRequest prepareReserveNow(ReserveNowParams params, int reservationId) {
        String idTag = params.getIdTag();
        return new ReserveNowRequest()
                .withConnectorId(params.getConnectorId())
                .withReservationId(reservationId)
                .withExpiryDate(params.getExpiry().toDateTime())
                .withIdTag(idTag)
                .withParentIdTag(userRepository.getParentIdtag(idTag));
    }

    private CancelReservationRequest prepareCancelReservation(CancelReservationParams params) {
        return new CancelReservationRequest()
                .withReservationId(params.getReservationId());
    }

    // -------------------------------------------------------------------------
    // Multiple Execution
    // -------------------------------------------------------------------------

    public int changeAvailability(ChangeAvailabilityParams params) {
        ChangeAvailabilityRequest req = this.prepareChangeAvailability(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Change Availability", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            ChangeAvailabilityResponseHandler handler = new ChangeAvailabilityResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).changeAvailabilityAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int changeConfiguration(ChangeConfigurationParams params) {
        ChangeConfigurationRequest req = this.prepareChangeConfiguration(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Change Configuration", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            ChangeConfigurationResponseHandler handler = new ChangeConfigurationResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).changeConfigurationAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int clearCache(MultipleChargePointSelect params) {
        ClearCacheRequest req = this.prepareClearCache();
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Clear Cache", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            ClearCacheResponseHandler handler = new ClearCacheResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).clearCacheAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int getDiagnostics(GetDiagnosticsParams params) {
        GetDiagnosticsRequest req = this.prepareGetDiagnostics(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Get Diagnostics", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            GetDiagnosticsResponseHandler handler = new GetDiagnosticsResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).getDiagnosticsAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int reset(ResetParams params) {
        ResetRequest req = this.prepareReset(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Reset", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            ResetResponseHandler handler = new ResetResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).resetAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int updateFirmware(UpdateFirmwareParams params) {
        UpdateFirmwareRequest req = this.prepareUpdateFirmware(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Update Firmware", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            UpdateFirmwareResponseHandler handler = new UpdateFirmwareResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).updateFirmwareAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int dataTransfer(DataTransferParams params) {
        DataTransferRequest req = this.prepareDataTransfer(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Data Transfer", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            DataTransferResponseHandler handler = new DataTransferResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).dataTransferAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int getConfiguration(GetConfigurationParams params) {
        GetConfigurationRequest req = this.prepareGetConfiguration(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Get Configuration", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            GetConfigurationResponseHandler handler = new GetConfigurationResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).getConfigurationAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int getLocalListVersion(MultipleChargePointSelect params) {
        GetLocalListVersionRequest req = this.prepareGetLocalListVersion();
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Get Local List Version", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            GetLocalListVersionResponseHandler handler = new GetLocalListVersionResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).getLocalListVersionAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    public int sendLocalList(SendLocalListParams params) {
        SendLocalListRequest req = this.prepareSendLocalList(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Send Local List", chargePointSelectList);

        for (ChargePointSelect c : chargePointSelectList) {
            String chargeBoxId = c.getChargeBoxId();
            SendLocalListResponseHandler handler = new SendLocalListResponseHandler(requestTask, chargeBoxId);
            create(c.getEndpointAddress()).sendLocalListAsync(req, chargeBoxId, handler);
        }

        return requestTaskStore.add(requestTask);
    }

    // -------------------------------------------------------------------------
    // Single Execution
    // -------------------------------------------------------------------------

    public int remoteStartTransaction(RemoteStartTransactionParams params) {
        RemoteStartTransactionRequest req = this.prepareRemoteStartTransaction(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Remote Start Transaction", chargePointSelectList);

        ChargePointSelect c = chargePointSelectList.get(0);

        String chargeBoxId = c.getChargeBoxId();
        RemoteStartTransactionResponseHandler handler = new RemoteStartTransactionResponseHandler(requestTask, chargeBoxId);
        create(c.getEndpointAddress()).remoteStartTransactionAsync(req, chargeBoxId, handler);

        return requestTaskStore.add(requestTask);
    }

    public int remoteStopTransaction(RemoteStopTransactionParams params) {
        RemoteStopTransactionRequest req = this.prepareRemoteStopTransaction(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Remote Stop Transaction", chargePointSelectList);

        ChargePointSelect c = chargePointSelectList.get(0);

        String chargeBoxId = c.getChargeBoxId();
        RemoteStopTransactionResponseHandler handler = new RemoteStopTransactionResponseHandler(requestTask, chargeBoxId);
        create(c.getEndpointAddress()).remoteStopTransactionAsync(req, chargeBoxId, handler);

        return requestTaskStore.add(requestTask);
    }

    public int unlockConnector(UnlockConnectorParams params) {
        UnlockConnectorRequest req = this.prepareUnlockConnector(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Unlock Connector", chargePointSelectList);

        ChargePointSelect c = chargePointSelectList.get(0);

        String chargeBoxId = c.getChargeBoxId();
        UnlockConnectorResponseHandler handler = new UnlockConnectorResponseHandler(requestTask, chargeBoxId);
        create(c.getEndpointAddress()).unlockConnectorAsync(req, chargeBoxId, handler);

        return requestTaskStore.add(requestTask);
    }

    public int reserveNow(ReserveNowParams params) {
        // Insert into DB
        Timestamp startTimestamp = new Timestamp(new DateTime().getMillis());
        Timestamp expiryTimestamp = new Timestamp(params.getExpiry().toDateTime().getMillis());
        int reservationId = reservationRepository.bookReservation(params.getIdTag(), params.getIdTag(),
                                                                  startTimestamp, expiryTimestamp);

        ReserveNowRequest req = this.prepareReserveNow(params, reservationId);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Reserve Now", chargePointSelectList);

        ChargePointSelect c = chargePointSelectList.get(0);

        String chargeBoxId = c.getChargeBoxId();
        ReserveNowResponseHandler handler = new ReserveNowResponseHandler(requestTask, chargeBoxId,
                                                                          reservationRepository, reservationId);
        create(c.getEndpointAddress()).reserveNowAsync(req, chargeBoxId, handler);

        return requestTaskStore.add(requestTask);
    }

    public int cancelReservation(CancelReservationParams params) {
        CancelReservationRequest req = this.prepareCancelReservation(params);
        List<ChargePointSelect> chargePointSelectList = params.getChargePointSelectList();
        RequestTask requestTask = new RequestTask(OcppVersion.V_15, "Cancel Reservation", chargePointSelectList);

        ChargePointSelect c = chargePointSelectList.get(0);

        String chargeBoxId = c.getChargeBoxId();
        CancelReservationResponseHandler handler = new CancelReservationResponseHandler(requestTask, chargeBoxId,
                                                                                        reservationRepository, params.getReservationId());
        create(c.getEndpointAddress()).cancelReservationAsync(req, chargeBoxId, handler);

        return requestTaskStore.add(requestTask);
    }
}