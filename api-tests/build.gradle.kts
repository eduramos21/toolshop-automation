plugins {
    id("toolshop.java-conventions")
}

dependencies {
    // The API client is in src/main, not src/test. It is framework code with a
    // second consumer already planned: ui-tests and data create their test data
    // through this API rather than through the database, so that the
    // application's own validation and side effects apply.
    api(project(":core"))
    api(libs.rest.assured)

    // REST Assured's object mapping is optional and absent without this. See the
    // catalog entry for why records rather than maps.
    api(libs.jackson.databind)

    api(libs.swagger.request.validator)

    api(platform(libs.allure.bom))
    api(libs.allure.jupiter)
}
