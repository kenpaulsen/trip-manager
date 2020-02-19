# Trip Manager
Web application for managing group travel. It is written to be backed by DynamoDB.

## To run locally:
Below are some tips on how to run this application locally for development. You may want to create aliases or
scripts to do these things if you plan to do them frequently.

### Build the application
* Clone the application (i.e. `git clone git@github.com:kenpaulsen/trip-manager.git`)
* From the `[trip-manager]` directory run `mvn clean install`.  This will build the `[trip-manager]/trip/target/trip`
war directory and the `[trip-manager]/trip/target/trip/trip.war` file. Either of these can be used to deploy the
server.

### Install and start GlassFish
* Download the [GlassFish 5 Web Profile](https://javaee.github.io/glassfish/download)
* Unzip it to `[glassfish-location]`
* Start GlassFish: `[glassfish-location]/glassfish5/glassfish/bin/asadmin start-domain`
* Deploy the application built in the previous step: `[glassfish-location]/glassfish5/glassfish/bin/asadmin deploy
[trip-manager]/trip/target/trip`

### Local Development and Testing
When run in `local` mode, no DynamoDB calls will be made. Instead it will simply interact with your local memory.
When the server is restarted, everything will be lost (this mode is only for local development without requiring
AWS / DynamoDB access). To enable / disable `local` mode, change the `Faces Servlet` `local` `init-param` to `true`
for `local` mode, or `false` for production mode (i.e. to use DynamoDB).

When local mode is enabled, the login values are as follows:
* Admin Users: Any email starting with `admin`; Password is `admin`.
* Normal Users: Any email starting with `user`; Password is `user`.

Since the users are faked and can't be reloaded from the database that isn't used, you will likely experience
problems when running in local mode, but most things should work enough to test / develop new features.

### Undeploy and/or Redeploy
* To undeploy the application: `[glassfish-location]/glassfish5/glassfish/bin/asadmin undeploy
[trip-manager]/trip/target/trip`
* To redeploy, undeploy then deploy with the commands above.

### Stop GlassFish
* To stop GlassFish: `[glassfish-location]/glassfish5/glassfish/bin/asadmin stop-domain`
