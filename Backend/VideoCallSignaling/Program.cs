using VideoCallSignaling.Hubs;
using VideoCallSignaling.Models;

var builder = WebApplication.CreateBuilder(args);

// ──────────────────────────────────────────────────────────────
//  Services
// ──────────────────────────────────────────────────────────────

// Register the room manager as a singleton so all hubs/controllers share state.
builder.Services.AddSingleton<RoomManager>();

// Add SignalR with optional detailed error messages in Development.
builder.Services.AddSignalR(options =>
{
    if (builder.Environment.IsDevelopment())
    {
        options.EnableDetailedErrors = true;
    }
    // Keep the connection alive with a 15-second ping.
    options.KeepAliveInterval = TimeSpan.FromSeconds(15);
    // Allow 30 seconds for the client to respond before considering it disconnected.
    options.ClientTimeoutInterval = TimeSpan.FromSeconds(30);
});

// Add MVC controllers (for the REST API).
builder.Services.AddControllers();

// ── CORS ─────────────────────────────────────────────────────
// Development: allow any origin so the Android emulator / browser can connect.
// Production: restrict to known origins.
builder.Services.AddCors(options =>
{
    // Permissive policy for development.
    options.AddPolicy("AllowAll", policy =>
    {
        policy
            .AllowAnyOrigin()
            .AllowAnyHeader()
            .AllowAnyMethod();
    });

    // Restrictive policy for production — update these origins as needed.
    options.AddPolicy("Production", policy =>
    {
        policy
            .WithOrigins(
                "https://yourdomain.com",
                "https://www.yourdomain.com")
            .AllowAnyHeader()
            .AllowAnyMethod()
            .AllowCredentials();
    });
});

// IIS integration (for hosting behind IIS).
builder.Services.Configure<IISServerOptions>(options =>
{
    options.AutomaticAuthentication = false;
});

// Logging is configured via appsettings.json / appsettings.Development.json.
builder.Logging.ClearProviders();
builder.Logging.AddConsole();
builder.Logging.AddDebug();

// ──────────────────────────────────────────────────────────────
//  Middleware pipeline
// ──────────────────────────────────────────────────────────────

var app = builder.Build();

// Enable WebSockets (required for SignalR WebSocket transport).
app.UseWebSockets();

// Use the permissive CORS policy in development; swap to "Production" when deploying.
if (app.Environment.IsDevelopment())
{
    app.UseCors("AllowAll");
    app.UseDeveloperExceptionPage();
}
else
{
    app.UseCors("Production");
}

app.UseRouting();

// Map the SignalR hub endpoint.
app.MapHub<VideoCallHub>("/videocallhub");

// Map REST API controllers.
app.MapControllers();

// Simple health-check / landing page.
app.MapGet("/", () => Results.Ok(new
{
    service = "VideoCall Signaling Server",
    status = "Running",
    hub = "/videocallhub",
    api = "/api/rooms",
    timestamp = DateTime.UtcNow
}));

app.Logger.LogInformation("VideoCall Signaling Server starting...");
app.Logger.LogInformation("SignalR Hub mapped at /videocallhub");
app.Logger.LogInformation("REST API mapped at /api/rooms");

app.Run();
