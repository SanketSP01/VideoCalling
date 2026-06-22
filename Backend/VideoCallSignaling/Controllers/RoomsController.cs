using Microsoft.AspNetCore.Mvc;
using VideoCallSignaling.Models;

namespace VideoCallSignaling.Controllers;

/// <summary>
/// REST API for inspecting and managing video call rooms.
/// These endpoints complement the real-time SignalR hub with
/// standard HTTP operations for admin/monitoring purposes.
/// </summary>
[ApiController]
[Route("api/rooms")]
public class RoomsController : ControllerBase
{
    private readonly RoomManager _roomManager;
    private readonly ILogger<RoomsController> _logger;

    public RoomsController(RoomManager roomManager, ILogger<RoomsController> logger)
    {
        _roomManager = roomManager;
        _logger = logger;
    }

    /// <summary>
    /// GET api/rooms — List all active rooms with participant counts.
    /// </summary>
    [HttpGet]
    public IActionResult GetAllRooms()
    {
        var rooms = _roomManager.GetAllRooms();

        var result = rooms.Select(r => new
        {
            r.RoomId,
            ParticipantCount = r.Participants.Count,
            Participants = r.Participants.Select(p => new
            {
                ConnectionId = p.Key,
                UserName = p.Value
            }),
            r.CreatedAt
        });

        return Ok(result);
    }

    /// <summary>
    /// GET api/rooms/{roomId} — Get details for a specific room.
    /// </summary>
    [HttpGet("{roomId}")]
    public IActionResult GetRoom(string roomId)
    {
        var room = _roomManager.GetRoom(roomId);

        if (room == null)
        {
            return NotFound(new { message = $"Room '{roomId}' not found." });
        }

        return Ok(new
        {
            room.RoomId,
            ParticipantCount = room.Participants.Count,
            Participants = room.Participants.Select(p => new
            {
                ConnectionId = p.Key,
                UserName = p.Value
            }),
            room.CreatedAt
        });
    }

    /// <summary>
    /// POST api/rooms — Create a new empty room.
    /// Body: { "roomId": "my-room-123" }
    /// Returns 409 Conflict if the room already exists.
    /// </summary>
    [HttpPost]
    public IActionResult CreateRoom([FromBody] CreateRoomRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.RoomId))
        {
            return BadRequest(new { message = "roomId is required." });
        }

        var created = _roomManager.EnsureRoomExists(request.RoomId);

        if (!created)
        {
            return Conflict(new { message = $"Room '{request.RoomId}' already exists." });
        }

        _logger.LogInformation("Room '{RoomId}' created via REST API.", request.RoomId);

        return CreatedAtAction(nameof(GetRoom), new { roomId = request.RoomId }, new
        {
            roomId = request.RoomId,
            message = $"Room '{request.RoomId}' created. Users can join via the SignalR hub."
        });
    }

    /// <summary>
    /// DELETE api/rooms/{roomId} — Forcibly delete a room.
    /// </summary>
    [HttpDelete("{roomId}")]
    public IActionResult DeleteRoom(string roomId)
    {
        var deleted = _roomManager.DeleteRoom(roomId);

        if (!deleted)
        {
            return NotFound(new { message = $"Room '{roomId}' not found." });
        }

        _logger.LogInformation("Room '{RoomId}' deleted via REST API.", roomId);
        return Ok(new { message = $"Room '{roomId}' has been deleted." });
    }
}

/// <summary>
/// Request body for POST api/rooms.
/// </summary>
public class CreateRoomRequest
{
    /// <summary>The desired room identifier.</summary>
    public string RoomId { get; set; } = string.Empty;
}
