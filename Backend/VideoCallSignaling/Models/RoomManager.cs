using System.Collections.Concurrent;

namespace VideoCallSignaling.Models;

/// <summary>
/// Represents a video call room that holds up to 2 participants.
/// </summary>
public class Room
{
    /// <summary>Unique identifier for this room.</summary>
    public string RoomId { get; set; } = string.Empty;

    /// <summary>
    /// Maps participant connectionId → userName.
    /// Locked via the room-level lock for thread-safe mutation.
    /// </summary>
    public Dictionary<string, string> Participants { get; set; } = new();

    /// <summary>UTC timestamp when the room was created.</summary>
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>Maximum number of participants allowed (1-to-1 calling).</summary>
    public const int MaxParticipants = 2;
}

/// <summary>
/// Result returned when a user attempts to join a room.
/// </summary>
public class JoinRoomResult
{
    /// <summary>Whether the join succeeded.</summary>
    public bool Success { get; set; }

    /// <summary>Human-readable message (e.g. "Joined room abc").</summary>
    public string Message { get; set; } = string.Empty;

    /// <summary>The room that was joined (null if the room was full).</summary>
    public Room? Room { get; set; }

    /// <summary>
    /// Other participants already in the room at the moment of joining.
    /// Key = connectionId, Value = userName.
    /// </summary>
    public Dictionary<string, string> ExistingParticipants { get; set; } = new();
}

/// <summary>
/// Singleton service that manages video call rooms.
/// Uses <see cref="ConcurrentDictionary{TKey,TValue}"/> for thread-safe
/// room storage and per-room locking for participant mutations.
/// </summary>
public class RoomManager
{
    private readonly ConcurrentDictionary<string, Room> _rooms = new();
    private readonly ILogger<RoomManager> _logger;

    /// <summary>Per-room locks to serialise participant mutations.</summary>
    private readonly ConcurrentDictionary<string, object> _roomLocks = new();

    public RoomManager(ILogger<RoomManager> logger)
    {
        _logger = logger;
    }

    // ──────────────────────────────────────────────────────────────
    //  Join / Leave
    // ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Creates the room if it doesn't exist and adds the user.
    /// Returns a <see cref="JoinRoomResult"/> indicating success/failure
    /// and the list of participants already present.
    /// </summary>
    public JoinRoomResult CreateOrJoinRoom(string roomId, string connectionId, string userName)
    {
        // Ensure the room exists.
        var room = _rooms.GetOrAdd(roomId, id => new Room { RoomId = id });
        var roomLock = _roomLocks.GetOrAdd(roomId, _ => new object());

        lock (roomLock)
        {
            // Already in this room? Treat as success (idempotent).
            if (room.Participants.ContainsKey(connectionId))
            {
                _logger.LogWarning("Connection {ConnectionId} already in room {RoomId}", connectionId, roomId);
                return new JoinRoomResult
                {
                    Success = true,
                    Message = "Already in room",
                    Room = room,
                    ExistingParticipants = new Dictionary<string, string>(
                        room.Participants.Where(p => p.Key != connectionId))
                };
            }

            // Room full?
            if (room.Participants.Count >= Room.MaxParticipants)
            {
                _logger.LogWarning("Room {RoomId} is full. Connection {ConnectionId} rejected.", roomId, connectionId);
                return new JoinRoomResult
                {
                    Success = false,
                    Message = "Room is full (max 2 participants)"
                };
            }

            // Snapshot existing participants BEFORE adding the new one.
            var existing = new Dictionary<string, string>(room.Participants);

            room.Participants[connectionId] = userName;
            _logger.LogInformation("User {UserName} ({ConnectionId}) joined room {RoomId}. Participants: {Count}",
                userName, connectionId, roomId, room.Participants.Count);

            return new JoinRoomResult
            {
                Success = true,
                Message = $"Joined room {roomId}",
                Room = room,
                ExistingParticipants = existing
            };
        }
    }

    /// <summary>
    /// Removes a connection from a specific room.
    /// Returns the userName of the removed user, or null if not found.
    /// Cleans up the room if it becomes empty.
    /// </summary>
    public string? LeaveRoom(string roomId, string connectionId)
    {
        if (!_rooms.TryGetValue(roomId, out var room))
            return null;

        var roomLock = _roomLocks.GetOrAdd(roomId, _ => new object());

        lock (roomLock)
        {
            if (!room.Participants.TryGetValue(connectionId, out var userName))
                return null;

            room.Participants.Remove(connectionId);
            _logger.LogInformation("User {UserName} ({ConnectionId}) left room {RoomId}. Remaining: {Count}",
                userName, connectionId, roomId, room.Participants.Count);

            // Clean up empty rooms.
            if (room.Participants.Count == 0)
            {
                _rooms.TryRemove(roomId, out _);
                _roomLocks.TryRemove(roomId, out _);
                _logger.LogInformation("Room {RoomId} is now empty and has been removed.", roomId);
            }

            return userName;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Queries
    // ──────────────────────────────────────────────────────────────

    /// <summary>Returns the <see cref="Room"/> for the given id, or null.</summary>
    public Room? GetRoom(string roomId)
    {
        _rooms.TryGetValue(roomId, out var room);
        return room;
    }

    /// <summary>Returns a snapshot list of all active rooms.</summary>
    public List<Room> GetAllRooms()
    {
        return _rooms.Values.ToList();
    }

    /// <summary>
    /// Finds the room that a given connectionId is currently in.
    /// Returns null if the connection is not in any room.
    /// </summary>
    public Room? GetRoomByConnectionId(string connectionId)
    {
        return _rooms.Values.FirstOrDefault(r => r.Participants.ContainsKey(connectionId));
    }

    // ──────────────────────────────────────────────────────────────
    //  Disconnect cleanup
    // ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Removes a connection from ALL rooms it belongs to.
    /// Returns a list of (roomId, userName) pairs for each room the user was removed from,
    /// along with the remaining participants in each room.
    /// </summary>
    public List<(string RoomId, string UserName, Dictionary<string, string> RemainingParticipants)>
        RemoveUserFromAllRooms(string connectionId)
    {
        var result = new List<(string RoomId, string UserName, Dictionary<string, string> RemainingParticipants)>();

        // Iterate over a snapshot of room keys to avoid mutation during enumeration.
        foreach (var roomId in _rooms.Keys.ToList())
        {
            if (!_rooms.TryGetValue(roomId, out var room))
                continue;

            var roomLock = _roomLocks.GetOrAdd(roomId, _ => new object());

            lock (roomLock)
            {
                if (!room.Participants.TryGetValue(connectionId, out var userName))
                    continue;

                room.Participants.Remove(connectionId);
                var remaining = new Dictionary<string, string>(room.Participants);

                _logger.LogInformation(
                    "Disconnect cleanup: removed {UserName} ({ConnectionId}) from room {RoomId}. Remaining: {Count}",
                    userName, connectionId, roomId, remaining.Count);

                result.Add((roomId, userName, remaining));

                // Clean up empty rooms.
                if (room.Participants.Count == 0)
                {
                    _rooms.TryRemove(roomId, out _);
                    _roomLocks.TryRemove(roomId, out _);
                    _logger.LogInformation("Room {RoomId} is now empty and has been removed.", roomId);
                }
            }
        }

        return result;
    }

    // ──────────────────────────────────────────────────────────────
    //  Admin
    // ──────────────────────────────────────────────────────────────

    /// <summary>
    /// Creates an empty room if it doesn't already exist.
    /// Returns true if the room was newly created, false if it already existed.
    /// </summary>
    public bool EnsureRoomExists(string roomId)
    {
        var created = false;
        _rooms.GetOrAdd(roomId, _ =>
        {
            created = true;
            return new Room { RoomId = roomId };
        });
        if (created)
        {
            _roomLocks.GetOrAdd(roomId, _ => new object());
            _logger.LogInformation("Room {RoomId} pre-created (empty).", roomId);
        }
        return created;
    }

    /// <summary>
    /// Forcibly deletes a room, disconnecting any participants logically.
    /// Returns true if the room existed and was removed.
    /// </summary>
    public bool DeleteRoom(string roomId)
    {
        if (_rooms.TryRemove(roomId, out _))
        {
            _roomLocks.TryRemove(roomId, out _);
            _logger.LogInformation("Room {RoomId} has been forcibly deleted.", roomId);
            return true;
        }
        return false;
    }
}
