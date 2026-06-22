using Microsoft.AspNetCore.SignalR;
using VideoCallSignaling.Models;

namespace VideoCallSignaling.Hubs;

/// <summary>
/// SignalR hub that handles WebRTC signaling for 1-to-1 video calls.
///
/// Client callback methods:
///   - UserJoined(string connectionId, string userName)
///   - UserLeft(string connectionId, string userName)
///   - ReceiveOffer(string connectionId, string sdp)
///   - ReceiveAnswer(string connectionId, string sdp)
///   - ReceiveIceCandidate(string connectionId, string candidate)
///   - RoomFull()
/// </summary>
public class VideoCallHub : Hub
{
    private readonly RoomManager _roomManager;
    private readonly ILogger<VideoCallHub> _logger;

    public VideoCallHub(RoomManager roomManager, ILogger<VideoCallHub> logger)
    {
        _roomManager = roomManager;
        _logger = logger;
    }

    // ──────────────────────────────────────────────────────────────
    //  Connection lifecycle
    // ──────────────────────────────────────────────────────────────

    /// <summary>Called when a new client connects to the hub.</summary>
    public override async Task OnConnectedAsync()
    {
        _logger.LogInformation("Client connected: {ConnectionId}", Context.ConnectionId);
        await base.OnConnectedAsync();
    }

    /// <summary>
    /// Called when a client disconnects (browser close, network drop, etc.).
    /// Removes the user from all rooms and notifies remaining participants.
    /// </summary>
    public override async Task OnDisconnectedAsync(Exception? exception)
    {
        var connectionId = Context.ConnectionId;

        if (exception != null)
        {
            _logger.LogWarning(exception, "Client disconnected with error: {ConnectionId}", connectionId);
        }
        else
        {
            _logger.LogInformation("Client disconnected: {ConnectionId}", connectionId);
        }

        // Remove from every room and notify peers.
        var removedRooms = _roomManager.RemoveUserFromAllRooms(connectionId);

        foreach (var (roomId, userName, remaining) in removedRooms)
        {
            // Notify each remaining participant that this user has left.
            foreach (var participantId in remaining.Keys)
            {
                await Clients.Client(participantId)
                    .SendAsync("UserLeft", connectionId, userName);
            }

            _logger.LogInformation(
                "Notified {Count} remaining participant(s) in room {RoomId} about {UserName} leaving.",
                remaining.Count, roomId, userName);
        }

        await base.OnDisconnectedAsync(exception);
    }

    // ──────────────────────────────────────────────────────────────
    //  Room management
    // ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Joins a room. If the room already has another user, both sides
    /// receive a <c>UserJoined</c> callback so they can begin the
    /// WebRTC offer/answer exchange.
    /// </summary>
    /// <param name="roomId">The room to join.</param>
    /// <param name="userName">Display name of the joining user.</param>
    public async Task JoinRoom(string roomId, string userName)
    {
        var connectionId = Context.ConnectionId;
        _logger.LogInformation("JoinRoom request: {UserName} ({ConnectionId}) → room {RoomId}",
            userName, connectionId, roomId);

        var result = _roomManager.CreateOrJoinRoom(roomId, connectionId, userName);

        if (!result.Success)
        {
            // Room is full — notify the caller only.
            _logger.LogWarning("Room {RoomId} is full. Sending RoomFull to {ConnectionId}", roomId, connectionId);
            await Clients.Caller.SendAsync("RoomFull");
            return;
        }

        // Add the caller to the SignalR group so we can broadcast later if needed.
        await Groups.AddToGroupAsync(connectionId, roomId);

        // Notify existing participants about the new joiner.
        foreach (var (existingId, existingName) in result.ExistingParticipants)
        {
            // Tell the existing user about the new joiner, and designate them as the initiator.
            await Clients.Client(existingId)
                .SendAsync("UserJoined", connectionId, userName, true);

            // Tell the new joiner about the existing user, and designate them as the responder.
            await Clients.Caller
                .SendAsync("UserJoined", existingId, existingName, false);
        }

        _logger.LogInformation(
            "User {UserName} ({ConnectionId}) successfully joined room {RoomId}. " +
            "Existing participants notified: {Count}",
            userName, connectionId, roomId, result.ExistingParticipants.Count);
    }

    /// <summary>
    /// Leaves a room and notifies remaining participants.
    /// </summary>
    /// <param name="roomId">The room to leave.</param>
    public async Task LeaveRoom(string roomId)
    {
        var connectionId = Context.ConnectionId;
        var userName = _roomManager.LeaveRoom(roomId, connectionId);

        if (userName == null)
        {
            _logger.LogWarning("LeaveRoom: {ConnectionId} was not in room {RoomId}", connectionId, roomId);
            return;
        }

        // Remove from SignalR group.
        await Groups.RemoveFromGroupAsync(connectionId, roomId);

        // Notify remaining participants.
        await Clients.Group(roomId).SendAsync("UserLeft", connectionId, userName);

        _logger.LogInformation("User {UserName} ({ConnectionId}) left room {RoomId}", userName, connectionId, roomId);
    }

    // ──────────────────────────────────────────────────────────────
    //  WebRTC signaling relay
    // ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Relays an SDP offer to the specified target connection.
    /// The caller's connectionId is included so the receiver knows who sent it.
    /// </summary>
    /// <param name="targetConnectionId">The peer to send the offer to.</param>
    /// <param name="sdp">The SDP offer string.</param>
    public async Task SendOffer(string targetConnectionId, string sdp)
    {
        _logger.LogDebug("SendOffer: {From} → {To}", Context.ConnectionId, targetConnectionId);
        await Clients.Client(targetConnectionId)
            .SendAsync("ReceiveOffer", Context.ConnectionId, sdp);
    }

    /// <summary>
    /// Relays an SDP answer to the specified target connection.
    /// </summary>
    /// <param name="targetConnectionId">The peer to send the answer to.</param>
    /// <param name="sdp">The SDP answer string.</param>
    public async Task SendAnswer(string targetConnectionId, string sdp)
    {
        _logger.LogDebug("SendAnswer: {From} → {To}", Context.ConnectionId, targetConnectionId);
        await Clients.Client(targetConnectionId)
            .SendAsync("ReceiveAnswer", Context.ConnectionId, sdp);
    }

    /// <summary>
    /// Relays an ICE candidate to the specified target connection.
    /// </summary>
    /// <param name="targetConnectionId">The peer to send the candidate to.</param>
    /// <param name="candidate">JSON string representing the ICE candidate.</param>
    public async Task SendIceCandidate(string targetConnectionId, string candidate)
    {
        _logger.LogDebug("SendIceCandidate: {From} → {To}", Context.ConnectionId, targetConnectionId);
        await Clients.Client(targetConnectionId)
            .SendAsync("ReceiveIceCandidate", Context.ConnectionId, candidate);
    }
}
